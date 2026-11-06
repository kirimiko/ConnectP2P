"""
High-Speed Framed TCP Transport Engine + Idle Power State Watchdog.

Features:
1. Streaming file transfer with 256 KB chunks (`SOCKET_BUFFER_SIZE`)
2. Breakpoint resume (`FILE_OFFER` -> `FILE_ACCEPT` with `resume_offset` -> `FILE_DATA` -> `FILE_ACK`)
3. End-to-end SHA-256 integrity verification
4. Large clipboard transfer over TCP (> 16 KB text or images)
5. Automatic Idle Power Watchdog:
   - Tracks active transfers (`active_transfers > 0`) and last transfer timestamp
   - Automatically triggers `on_idle_teardown()` after `idle_timeout_sec` (default 15s)
     of zero transfer activity so the Wi-Fi P2P group is torn down immediately.
"""

from __future__ import annotations

import hashlib
import logging
import os
import socket
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from protocol import (
    DATA_TCP_PORT,
    P2P_IDLE_TEARDOWN_SECONDS,
    SOCKET_BUFFER_SIZE,
    FrameType,
    PowerState,
    compute_clip_hash,
    compute_file_sha256,
    create_aes_ctr_cipher,
    decrypt_payload_if_needed,
    encode_frame,
    encode_stream_header_only,
    read_frame_header_from_socket,
    recv_exact,
)

logger = logging.getLogger("ConnectP2P.Transport")


@dataclass
class TransferProgress:
    file_id: str
    file_name: str
    transferred_bytes: int
    total_bytes: int
    direction: str  # "send" | "receive"
    speed_mbps: float
    resumed_from: int = 0


class IdlePowerWatchdog:
    """
    Event-driven idle timer that guarantees the high-power Wi-Fi P2P burst link
    is torn down automatically once no file transfer has occurred for `idle_timeout_sec`.
    """

    def __init__(
        self,
        idle_timeout_sec: float = P2P_IDLE_TEARDOWN_SECONDS,
        on_state_change: Optional[Callable[[PowerState], None]] = None,
        on_idle_teardown: Optional[Callable[[], None]] = None,
    ) -> None:
        self.idle_timeout_sec = idle_timeout_sec
        self.on_state_change = on_state_change
        self.on_idle_teardown = on_idle_teardown

        self._lock = threading.Lock()
        self._state = PowerState.DEEP_IDLE
        self._active_transfers = 0
        self._timer: Optional[threading.Timer] = None

    @property
    def state(self) -> PowerState:
        with self._lock:
            return self._state

    @property
    def active_transfers(self) -> int:
        with self._lock:
            return self._active_transfers

    def set_state(self, new_state: PowerState) -> None:
        changed = False
        with self._lock:
            if self._state != new_state:
                self._state = new_state
                changed = True
            if new_state == PowerState.DEEP_IDLE and self._timer:
                self._timer.cancel()
                self._timer = None
        if changed and self.on_state_change:
            self.on_state_change(new_state)

    def on_transfer_started(self) -> None:
        with self._lock:
            self._active_transfers += 1
            if self._timer:
                self._timer.cancel()
                self._timer = None
        self.set_state(PowerState.P2P_BURST_ACTIVE)

    def on_transfer_finished(self) -> None:
        with self._lock:
            self._active_transfers = max(0, self._active_transfers - 1)
            remaining = self._active_transfers
            if remaining == 0:
                if self._timer:
                    self._timer.cancel()
                self._timer = threading.Timer(self.idle_timeout_sec, self._handle_timeout)
                self._timer.daemon = True
                self._timer.start()

    def touch_activity(self, timeout_sec: Optional[float] = None) -> None:
        """Reset countdown when a P2P handshake or burst transfer completes."""
        wait_sec = timeout_sec if timeout_sec is not None else self.idle_timeout_sec
        with self._lock:
            if self._active_transfers > 0:
                return
            if self._timer:
                self._timer.cancel()
            self._timer = threading.Timer(wait_sec, self._handle_timeout)
            self._timer.daemon = True
            self._timer.start()

    def force_idle_now(self) -> None:
        with self._lock:
            self._active_transfers = 0
            if self._timer:
                self._timer.cancel()
                self._timer = None
        self._handle_timeout()

    def _handle_timeout(self) -> None:
        with self._lock:
            if self._active_transfers > 0:
                return
            self._timer = None
        logger.info(
            "Idle timeout (%.1fs) reached with 0 active transfers -> tearing down Wi-Fi P2P burst link",
            self.idle_timeout_sec,
        )
        if self.on_idle_teardown:
            try:
                self.on_idle_teardown()
            except Exception as exc:
                logger.warning("Error during idle teardown: %s", exc)
        self.set_state(PowerState.DEEP_IDLE)


class P2pDataTransportEngine:
    """
    TCP Server & Client for high-speed file transfer and large clipboard sync over Wi-Fi P2P.
    """

    def __init__(
        self,
        device_id: str,
        device_name: str,
        download_dir: Path,
        listen_port: int = DATA_TCP_PORT,
        watchdog: Optional[IdlePowerWatchdog] = None,
        on_progress: Optional[Callable[[TransferProgress], None]] = None,
        on_file_received: Optional[Callable[[Path, str, bool], None]] = None,
        on_clipboard_received: Optional[Callable[[str, str, str], None]] = None,
        on_control_frame_received: Optional[Callable[[FrameType, dict, str], None]] = None,
        is_sender_authorized: Optional[Callable[[str, str, str], bool]] = None,
    ) -> None:
        self.device_id = device_id
        self.device_name = device_name
        self.download_dir = Path(download_dir)
        self.download_dir.mkdir(parents=True, exist_ok=True)
        self.listen_port = listen_port
        self.watchdog = watchdog or IdlePowerWatchdog()
        self.on_progress = on_progress
        self.on_file_received = on_file_received
        self.on_clipboard_received = on_clipboard_received
        self.on_control_frame_received = on_control_frame_received
        self.is_sender_authorized = is_sender_authorized

        self._server_sock: Optional[socket.socket] = None
        self._running = False
        self._accept_thread: Optional[threading.Thread] = None

    @property
    def bound_port(self) -> int:
        if self._server_sock:
            return self._server_sock.getsockname()[1]
        return self.listen_port

    def start_server(self) -> int:
        if self._running and self._server_sock:
            return self.bound_port

        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCKET_BUFFER_SIZE)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, SOCKET_BUFFER_SIZE)
        srv.bind(("0.0.0.0", self.listen_port))
        srv.listen(8)

        self._server_sock = srv
        self._running = True
        self._accept_thread = threading.Thread(
            target=self._accept_loop,
            name="P2PTcpAcceptLoop",
            daemon=True,
        )
        self._accept_thread.start()
        logger.info("TCP data server listening on 0.0.0.0:%d", self.bound_port)
        return self.bound_port

    def stop_server(self) -> None:
        self._running = False
        if self._server_sock:
            try:
                self._server_sock.close()
            except OSError:
                pass
            self._server_sock = None
        if self._accept_thread and self._accept_thread.is_alive():
            self._accept_thread.join(timeout=1.5)

    def probe_peer_alive(
        self,
        target_ip: str,
        target_port: int = DATA_TCP_PORT,
        timeout: float = 1.5,
    ) -> bool:
        """
        Lightweight TCP PING_PONG probe to verify if a connected peer is still online & reachable.
        Does NOT trigger the high-power P2P burst watchdog.
        """
        try:
            with socket.create_connection((target_ip, target_port), timeout=timeout) as sock:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                sock.settimeout(timeout)
                ping_frame = encode_frame(
                    FrameType.PING_PONG,
                    {"ping": True, "device_id": self.device_id, "device_name": self.device_name},
                )
                sock.sendall(ping_frame)
                ftype, hdr = read_frame_header_from_socket(sock)
                psize = int(hdr.get("payload_size", 0))
                if psize > 0:
                    recv_exact(sock, psize)
                return ftype == FrameType.PING_PONG
        except Exception:
            return False

    def send_control_frame_over_tcp(
        self,
        target_ip: str,
        frame_bytes: bytes,
        target_port: int = DATA_TCP_PORT,
        timeout: float = 1.5,
    ) -> bool:
        """Deliver a control frame (`PAIR_REQUEST`, `PAIR_RESPONSE`, etc.) over TCP to wake background peers."""
        try:
            with socket.create_connection((target_ip, target_port), timeout=timeout) as sock:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                sock.sendall(frame_bytes)
            return True
        except OSError:
            return False

    def send_clipboard_over_tcp(
        self,
        target_ip: str,
        text: str,
        target_port: int = DATA_TCP_PORT,
        timeout: float = 2.5,
        touch_burst_watchdog: bool = False,
    ) -> bool:
        """Send clipboard payload over TCP (wakes OEM Android background freezer in < 30ms)."""
        payload = text.encode("utf-8")
        clip_hash = compute_clip_hash(payload)
        if touch_burst_watchdog:
            self.watchdog.on_transfer_started()
        try:
            with socket.create_connection((target_ip, target_port), timeout=timeout) as sock:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                frame = encode_frame(
                    FrameType.CLIPBOARD_SYNC,
                    {
                        "device_id": self.device_id,
                        "device_name": self.device_name,
                        "clip_id": clip_hash,
                        "mime": "text/plain",
                        "timestamp": int(time.time() * 1000),
                        "origin_device_id": self.device_id,
                    },
                    payload=payload,
                )
                sock.sendall(frame)
            return True
        except OSError as exc:
            logger.debug("TCP clipboard send to %s:%d skipped/failed: %s", target_ip, target_port, exc)
            return False
        finally:
            if touch_burst_watchdog:
                self.watchdog.on_transfer_finished()

    def send_file(
        self,
        target_ip: str,
        file_path: str | Path,
        target_port: int = DATA_TCP_PORT,
        file_id: Optional[str] = None,
        timeout: float = 15.0,
    ) -> bool:
        """
        Send a file over Wi-Fi P2P TCP with:
        - Automatic breakpoint resume (`resume_offset`)
        - 256 KB high-throughput socket streaming
        - End-to-end SHA-256 verification (`FILE_ACK`)
        - Strict `IdlePowerWatchdog` lifecycle tracking
        """
        path = Path(file_path)
        if not path.is_file():
            raise FileNotFoundError(f"File not found: {path}")

        file_size = path.stat().st_size
        sha256_hex = compute_file_sha256(str(path))
        fid = file_id or sha256_hex[:16] or uuid.uuid4().hex[:16]

        self.watchdog.on_transfer_started()
        try:
            with socket.create_connection((target_ip, target_port), timeout=timeout) as sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, SOCKET_BUFFER_SIZE)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

                # Step 1: Send FILE_OFFER
                offer_frame = encode_frame(
                    FrameType.FILE_OFFER,
                    {
                        "file_id": fid,
                        "file_name": path.name,
                        "file_size": file_size,
                        "sha256": sha256_hex,
                        "sender_id": self.device_id,
                        "sender_name": self.device_name,
                    },
                )
                sock.sendall(offer_frame)

                # Step 2: Receive FILE_ACCEPT (with optional resume_offset)
                ftype, accept_hdr = read_frame_header_from_socket(sock)
                if accept_hdr.get("payload_size", 0) > 0:
                    recv_exact(sock, int(accept_hdr["payload_size"]))

                if ftype != FrameType.FILE_ACCEPT or not accept_hdr.get("accepted", False):
                    logger.warning("Peer rejected file offer for %s", path.name)
                    return False

                resume_offset = max(0, min(file_size, int(accept_hdr.get("resume_offset", 0))))
                remaining = file_size - resume_offset

                # Step 3: Send AES-256-GCM encrypted FILE_DATA header + AES-256-CTR encrypted binary stream
                stream_iv = os.urandom(16)
                ctr_cipher = create_aes_ctr_cipher(stream_iv)
                data_hdr_frame = encode_stream_header_only(
                    FrameType.FILE_DATA,
                    {
                        "file_id": fid,
                        "offset": resume_offset,
                        "enc_stream_iv": stream_iv.hex(),
                    },
                    stream_payload_size=remaining,
                )
                sock.sendall(data_hdr_frame)

                start_ts = time.perf_counter()
                sent_bytes = resume_offset
                with open(path, "rb") as f:
                    if resume_offset > 0:
                        f.seek(resume_offset)
                    while sent_bytes < file_size:
                        chunk = f.read(SOCKET_BUFFER_SIZE)
                        if not chunk:
                            break
                        sock.sendall(ctr_cipher.encrypt(chunk))
                        sent_bytes += len(chunk)
                        if self.on_progress:
                            elapsed = max(1e-3, time.perf_counter() - start_ts)
                            speed_mbps = ((sent_bytes - resume_offset) / (1024 * 1024)) / elapsed
                            self.on_progress(
                                TransferProgress(
                                    file_id=fid,
                                    file_name=path.name,
                                    transferred_bytes=sent_bytes,
                                    total_bytes=file_size,
                                    direction="send",
                                    speed_mbps=speed_mbps,
                                    resumed_from=resume_offset,
                                )
                            )

                # Step 4: Wait for FILE_ACK
                ack_type, ack_hdr = read_frame_header_from_socket(sock)
                if ack_hdr.get("payload_size", 0) > 0:
                    recv_exact(sock, int(ack_hdr["payload_size"]))

                if ack_type == FrameType.FILE_ACK and ack_hdr.get("success", False):
                    logger.info("File %s sent and verified (SHA-256 OK)", path.name)
                    return True
                logger.error("File %s failed verification on peer: %s", path.name, ack_hdr)
                return False
        finally:
            self.watchdog.on_transfer_finished()

    def _accept_loop(self) -> None:
        while self._running and self._server_sock:
            try:
                conn, addr = self._server_sock.accept()
            except OSError:
                break
            t = threading.Thread(
                target=self._handle_client_conn,
                args=(conn, addr),
                name=f"P2PConn-{addr[0]}",
                daemon=True,
            )
            t.start()

    def _handle_client_conn(self, conn: socket.socket, addr: tuple) -> None:
        did_start_burst = False
        try:
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCKET_BUFFER_SIZE)
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.settimeout(30.0)

            ftype, header = read_frame_header_from_socket(conn)
            payload_size = int(header.get("payload_size", 0))
            sender_id = str(header.get("device_id") or header.get("sender_id") or "")
            sender_name = str(header.get("device_name") or header.get("sender_name") or addr[0])

            if ftype == FrameType.CLIPBOARD_SYNC:
                wire_payload = recv_exact(conn, payload_size) if payload_size > 0 else b""
                if self.is_sender_authorized and not self.is_sender_authorized(sender_id, sender_name, addr[0]):
                    logger.warning("Blocked unauthorized clipboard sync from %s (%s)", sender_name, addr[0])
                    return
                payload = decrypt_payload_if_needed(header, wire_payload)
                text = payload.decode("utf-8", errors="replace")
                clip_id = header.get("clip_id") or compute_clip_hash(payload)
                if self.on_clipboard_received:
                    self.on_clipboard_received(text, clip_id, sender_name)

            elif ftype == FrameType.FILE_OFFER:
                if payload_size > 0:
                    recv_exact(conn, payload_size)
                if self.is_sender_authorized and not self.is_sender_authorized(sender_id, sender_name, addr[0]):
                    logger.warning("Blocked unauthorized file offer from %s (%s)", sender_name, addr[0])
                    reject_frame = encode_frame(
                        FrameType.FILE_ACCEPT,
                        {"file_id": header.get("file_id", ""), "accepted": False, "resume_offset": 0},
                    )
                    conn.sendall(reject_frame)
                    return
                did_start_burst = True
                self.watchdog.on_transfer_started()
                self._handle_incoming_file_transfer(conn, addr, header)

            elif ftype == FrameType.PING_PONG:
                if payload_size > 0:
                    recv_exact(conn, payload_size)
                pong = encode_frame(FrameType.PING_PONG, {"pong": True, "device_id": self.device_id})
                conn.sendall(pong)

            else:
                if payload_size > 0:
                    recv_exact(conn, payload_size)
                if self.on_control_frame_received:
                    self.on_control_frame_received(ftype, header, addr[0])

        except Exception as exc:
            logger.error("Error handling TCP connection from %s: %s", addr, exc)
        finally:
            try:
                conn.close()
            except OSError:
                pass
            if did_start_burst:
                self.watchdog.on_transfer_finished()

    def _handle_incoming_file_transfer(self, conn: socket.socket, addr: tuple, offer_hdr: dict) -> None:
        file_id = str(offer_hdr.get("file_id", "unknown"))
        raw_name = os.path.basename(str(offer_hdr.get("file_name", f"file_{file_id}.bin")))
        file_size = int(offer_hdr.get("file_size", 0))
        expected_sha256 = str(offer_hdr.get("sha256", "")).lower()
        sender_name = str(offer_hdr.get("sender_name", addr[0]))

        part_path = self.download_dir / f"{raw_name}.{file_id}.part"
        final_path = self.download_dir / raw_name

        # Check existing .part file for breakpoint resume
        resume_offset = 0
        if part_path.exists():
            existing_size = part_path.stat().st_size
            if 0 < existing_size < file_size:
                resume_offset = existing_size
            elif existing_size >= file_size:
                part_path.unlink(missing_ok=True)

        accept_frame = encode_frame(
            FrameType.FILE_ACCEPT,
            {
                "file_id": file_id,
                "accepted": True,
                "resume_offset": resume_offset,
            },
        )
        conn.sendall(accept_frame)

        # Read FILE_DATA frame header
        data_type, data_hdr = read_frame_header_from_socket(conn)
        if data_type != FrameType.FILE_DATA:
            raise ValueError(f"Expected FILE_DATA, got {data_type}")

        stream_len = int(data_hdr.get("payload_size", file_size - resume_offset))
        iv_hex = data_hdr.get("enc_stream_iv")
        ctr_cipher = create_aes_ctr_cipher(bytes.fromhex(str(iv_hex))) if iv_hex else None

        mode = "ab" if resume_offset > 0 else "wb"
        received_total = resume_offset
        start_ts = time.perf_counter()

        with open(part_path, mode) as out_f:
            remaining = stream_len
            while remaining > 0:
                chunk = conn.recv(min(SOCKET_BUFFER_SIZE, remaining))
                if not chunk:
                    raise ConnectionError(f"Connection interrupted at {received_total}/{file_size} bytes")
                plain_chunk = ctr_cipher.decrypt(chunk) if ctr_cipher is not None else chunk
                out_f.write(plain_chunk)
                remaining -= len(chunk)
                received_total += len(chunk)
                if self.on_progress:
                    elapsed = max(1e-3, time.perf_counter() - start_ts)
                    speed_mbps = ((received_total - resume_offset) / (1024 * 1024)) / elapsed
                    self.on_progress(
                        TransferProgress(
                            file_id=file_id,
                            file_name=raw_name,
                            transferred_bytes=received_total,
                            total_bytes=file_size,
                            direction="receive",
                            speed_mbps=speed_mbps,
                            resumed_from=resume_offset,
                        )
                    )

        actual_sha256 = compute_file_sha256(str(part_path)).lower()
        verified = (not expected_sha256) or (actual_sha256 == expected_sha256)

        if verified:
            if final_path.exists():
                stem, suffix = final_path.stem, final_path.suffix
                final_path = self.download_dir / f"{stem}_{int(time.time())}{suffix}"
            part_path.replace(final_path)
            logger.info("Received file %s (%d bytes) verified OK -> %s", raw_name, file_size, final_path)
        else:
            part_path.unlink(missing_ok=True)
            logger.error(
                "SHA-256 mismatch for %s: expected %s, got %s",
                raw_name,
                expected_sha256,
                actual_sha256,
            )

        ack_frame = encode_frame(
            FrameType.FILE_ACK,
            {
                "file_id": file_id,
                "success": verified,
                "verified_sha256": actual_sha256,
            },
        )
        conn.sendall(ack_frame)

        if self.on_file_received:
            self.on_file_received(final_path if verified else part_path, sender_name, verified)
