"""
Ultra-Low-Power Control & Micro-Data Channel for ConnectP2P.

Responsibilities:
1. Event-driven signaling (no periodic high-frequency polling loops!):
   - Sends/receives `HELLO` presence announcements only when requested or on state changes
   - Sends/receives inline `CLIPBOARD_SYNC` frames (<= 16 KB) directly without spinning up Wi-Fi P2P
   - Sends/receives `P2P_WAKE_REQUEST`, `P2P_GROUP_READY`, and `P2P_TEARDOWN` to orchestrate
     on-demand Wi-Fi P2P burst transfers
2. Supports both:
   - Event-driven UDP Unicast/Multicast socket (Port 45820)
   - BLE GATT Client/Scanner via `bleak` (if installed on Windows) using Service UUID
     `0000c0de-0000-1000-8000-00805f9b34fb`
"""

from __future__ import annotations

import logging
import socket
import struct
import subprocess
import threading
import time
from typing import Any, Callable, Dict, Optional

from protocol import (
    BLE_CLIPBOARD_CHAR_UUID,
    BLE_CONTROL_CHAR_UUID,
    BLE_SERVICE_UUID,
    CONTROL_UDP_PORT,
    DATA_TCP_PORT,
    MAX_INLINE_CLIPBOARD_BYTES,
    MULTICAST_GROUP,
    BleChunkReassembler,
    FrameType,
    PeerInfo,
    chunk_ble_payload,
    compute_clip_hash,
    decode_frame_from_bytes,
    encode_frame,
)

logger = logging.getLogger("ConnectP2P.Control")


class LowPowerControlChannel:
    """
    Event-driven control & micro-clipboard channel.
    Blocks on `socket.recvfrom` in a single daemon thread (0% CPU when idle).
    """

    def __init__(
        self,
        device_id: str,
        device_name: str,
        platform: str = "windows",
        udp_port: int = CONTROL_UDP_PORT,
        data_port: int = DATA_TCP_PORT,
        on_peer_discovered: Optional[Callable[[PeerInfo], None]] = None,
        on_clipboard_received: Optional[Callable[[str, str, str], None]] = None,
        on_p2p_wake_request: Optional[Callable[[PeerInfo, Dict[str, Any]], None]] = None,
        on_p2p_group_ready: Optional[Callable[[PeerInfo, Dict[str, Any]], None]] = None,
        on_p2p_teardown: Optional[Callable[[PeerInfo], None]] = None,
        on_pair_request: Optional[Callable[[PeerInfo, Dict[str, Any]], None]] = None,
        on_pair_response: Optional[Callable[[PeerInfo, bool], None]] = None,
    ) -> None:
        self.device_id = device_id
        self.device_name = device_name
        self.platform = platform
        self.udp_port = udp_port
        self.data_port = data_port

        self.on_peer_discovered = on_peer_discovered
        self.on_clipboard_received = on_clipboard_received
        self.on_p2p_wake_request = on_p2p_wake_request
        self.on_p2p_group_ready = on_p2p_group_ready
        self.on_p2p_teardown = on_p2p_teardown
        self.on_pair_request = on_pair_request
        self.on_pair_response = on_pair_response

        self.hotspot_info: Optional[Dict[str, str]] = None
        self._peers: Dict[str, PeerInfo] = {}
        self._peers_lock = threading.Lock()
        self._sock: Optional[socket.socket] = None
        self._running = False
        self._recv_thread: Optional[threading.Thread] = None
        self._ble_reassembler = BleChunkReassembler()

    @property
    def peers(self) -> Dict[str, PeerInfo]:
        with self._peers_lock:
            return dict(self._peers)

    def register_manual_peer(
        self,
        ip: str,
        device_name: str = "Android-P2PPeer",
        device_id: Optional[str] = None,
        data_port: int = DATA_TCP_PORT,
    ) -> PeerInfo:
        pid = device_id or f"peer-{ip}"
        peer = PeerInfo(
            device_id=pid,
            device_name=device_name,
            platform="android",
            ip=ip,
            data_port=data_port,
            last_seen=time.time(),
        )
        with self._peers_lock:
            self._peers[pid] = peer
        if self.on_peer_discovered:
            self.on_peer_discovered(peer)
        return peer

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("", self.udp_port))

        # Critical on Windows: prevent ICMP Port Unreachable from raising WinError 10054 (ConnectionResetError)
        # on subsequent recvfrom() calls and killing the UDP listener thread!
        if hasattr(socket, "SIO_UDP_CONNRESET"):
            try:
                sock.ioctl(socket.SIO_UDP_CONNRESET, False)
            except OSError:
                pass

        # Join multicast group for zero-config discovery on same Wi-Fi or P2P subnet
        try:
            mreq = struct.pack("4s4s", socket.inet_aton(MULTICAST_GROUP), socket.inet_aton("0.0.0.0"))
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        except OSError as exc:
            logger.debug("Multicast join skipped: %s", exc)

        self._sock = sock
        self._recv_thread = threading.Thread(
            target=self._udp_receive_loop,
            name="LowPowerControlUDP",
            daemon=True,
        )
        self._recv_thread.start()
        logger.info("Low-power control channel listening on UDP port %d", self.udp_port)

    def stop(self) -> None:
        self._running = False
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None
        if self._recv_thread and self._recv_thread.is_alive():
            self._recv_thread.join(timeout=1.5)

    def announce_presence_once(self, target_ip: Optional[str] = None) -> None:
        """
        Send a single event-driven HELLO packet (no background timer loop!).
        """
        frame = encode_frame(FrameType.HELLO, self._hello_header(is_reply=False))
        self._send_control_bytes(frame, target_ip=target_ip)

    def _hello_header(self, is_reply: bool) -> Dict[str, Any]:
        hdr: Dict[str, Any] = {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "platform": self.platform,
            "data_port": self.data_port,
            "is_reply": is_reply,
            "timestamp": int(time.time() * 1000),
        }
        if self.hotspot_info:
            hdr["hotspot_ssid"] = self.hotspot_info.get("ssid", "")
            hdr["hotspot_passphrase"] = self.hotspot_info.get("passphrase", "")
            hdr["hotspot_ip"] = self.hotspot_info.get("go_ip", "")
        return hdr

    def send_inline_clipboard(
        self,
        text: str,
        target_ip: Optional[str] = None,
        target_device_id: Optional[str] = None,
    ) -> bool:
        """
        Send small text clipboard (<= 16KB) over the low-power control channel
        WITHOUT waking up Wi-Fi P2P group!
        Returns True if sent inline, False if payload > 16KB (caller should use P2P burst).
        """
        payload = text.encode("utf-8")
        if len(payload) > MAX_INLINE_CLIPBOARD_BYTES:
            return False

        clip_hash = compute_clip_hash(payload)
        hdr: Dict[str, Any] = {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "clip_id": clip_hash,
            "mime": "text/plain",
            "timestamp": int(time.time() * 1000),
            "origin_device_id": self.device_id,
        }
        if target_device_id:
            hdr["target_device_id"] = target_device_id
        frame = encode_frame(
            FrameType.CLIPBOARD_SYNC,
            hdr,
            payload=payload,
        )
        self._send_control_bytes(frame, target_ip=target_ip)
        return True

    def send_p2p_wake_request(
        self,
        reason: str = "file_transfer",
        prefer_go_platform: str = "android",
        target_ip: Optional[str] = None,
        target_device_id: Optional[str] = None,
    ) -> None:
        """
        Request peer to wake up Wi-Fi P2P Autonomous Group Owner on demand.
        """
        hdr: Dict[str, Any] = {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "platform": self.platform,
            "reason": reason,
            "prefer_go_platform": prefer_go_platform,
            "timestamp": int(time.time() * 1000),
        }
        if target_device_id:
            hdr["target_device_id"] = target_device_id
        frame = encode_frame(FrameType.P2P_WAKE_REQUEST, hdr)
        self._send_control_bytes(frame, target_ip=target_ip)

    def send_p2p_group_ready(
        self,
        ssid: str,
        passphrase: str,
        go_ip: str,
        port: int = DATA_TCP_PORT,
        target_ip: Optional[str] = None,
        target_device_id: Optional[str] = None,
    ) -> None:
        """
        Notify peer that our Wi-Fi P2P Autonomous Group is ready for fast connection.
        """
        hdr: Dict[str, Any] = {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "platform": self.platform,
            "ssid": ssid,
            "passphrase": passphrase,
            "go_ip": go_ip,
            "port": port,
            "timestamp": int(time.time() * 1000),
        }
        if target_device_id:
            hdr["target_device_id"] = target_device_id
        frame = encode_frame(FrameType.P2P_GROUP_READY, hdr)
        self._send_control_bytes(frame, target_ip=target_ip)

    def send_p2p_teardown(
        self,
        target_ip: Optional[str] = None,
        target_device_id: Optional[str] = None,
    ) -> bytes:
        """
        Notify peer that transfer is complete or connection is disconnected.
        """
        hdr: Dict[str, Any] = {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "timestamp": int(time.time() * 1000),
        }
        if target_device_id and not target_device_id.startswith("p2p-"):
            hdr["target_device_id"] = target_device_id
        frame = encode_frame(FrameType.P2P_TEARDOWN, hdr)
        self._send_control_bytes(frame, target_ip=target_ip)
        return frame

    def send_pair_request(
        self,
        target_ip: Optional[str] = None,
        target_device_id: Optional[str] = None,
    ) -> bytes:
        """Send an explicit connection/pairing request to a peer."""
        hdr: Dict[str, Any] = {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "platform": self.platform,
            "data_port": self.data_port,
            "timestamp": int(time.time() * 1000),
        }
        if target_device_id and not target_device_id.startswith("p2p-"):
            hdr["target_device_id"] = target_device_id
        frame = encode_frame(FrameType.PAIR_REQUEST, hdr)
        self._send_control_bytes(frame, target_ip=target_ip)
        return frame

    def send_pair_response(
        self,
        accepted: bool,
        target_ip: Optional[str] = None,
        target_device_id: Optional[str] = None,
    ) -> bytes:
        """Send an explicit accept/reject response to a peer's connection request."""
        hdr: Dict[str, Any] = {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "platform": self.platform,
            "data_port": self.data_port,
            "accepted": bool(accepted),
            "timestamp": int(time.time() * 1000),
        }
        if target_device_id and not target_device_id.startswith("p2p-"):
            hdr["target_device_id"] = target_device_id
        frame = encode_frame(FrameType.PAIR_RESPONSE, hdr)
        self._send_control_bytes(frame, target_ip=target_ip)
        return frame

    def handle_ble_notification_chunk(self, chunk: bytes, sender_address: str = "") -> None:
        """
        Feed an incoming BLE GATT notification chunk from Android's BLE GATT server/client.
        Once all chunks of a frame are reassembled, dispatches to `_handle_raw_frame`.
        """
        full_frame = self._ble_reassembler.feed(chunk)
        if full_frame is not None:
            self._handle_raw_frame(full_frame, sender_ip=sender_address)

    def upsert_discovered_peer(self, peer: PeerInfo, notify: bool = True) -> None:
        with self._peers_lock:
            existing = self._peers.get(peer.device_id)
            if existing is None:
                self._peers[peer.device_id] = peer
            else:
                existing.device_name = peer.device_name or existing.device_name
                if peer.ip:
                    existing.ip = peer.ip
                if peer.p2p_ssid:
                    existing.p2p_ssid = peer.p2p_ssid
                if peer.p2p_passphrase:
                    existing.p2p_passphrase = peer.p2p_passphrase
                if peer.p2p_go_ip:
                    existing.p2p_go_ip = peer.p2p_go_ip
                existing.last_seen = time.time()
                peer = existing
        if notify and self.on_peer_discovered:
            self.on_peer_discovered(peer)

    def _send_control_bytes(self, frame: bytes, target_ip: Optional[str] = None) -> None:
        if not self._sock:
            return
        targets = set()
        if target_ip:
            targets.add((target_ip, self.udp_port))
        else:
            with self._peers_lock:
                for p in self._peers.values():
                    if p.ip:
                        targets.add((p.ip, self.udp_port))
            targets.add((MULTICAST_GROUP, self.udp_port))
            targets.add(("255.255.255.255", self.udp_port))
            targets.add(("192.168.49.1", self.udp_port))
            targets.add(("192.168.49.255", self.udp_port))
            targets.add(("192.168.137.1", self.udp_port))
            targets.add(("192.168.137.255", self.udp_port))
            try:
                _, _, local_ips = socket.gethostbyname_ex(socket.gethostname())
                for lip in local_ips:
                    parts = lip.split(".")
                    if len(parts) == 4 and not lip.startswith("127."):
                        targets.add((f"{parts[0]}.{parts[1]}.{parts[2]}.255", self.udp_port))
                        targets.add((f"{parts[0]}.{parts[1]}.255.255", self.udp_port))
            except OSError:
                pass
            for arp_ip in self._get_arp_hotspot_peers():
                targets.add((arp_ip, self.udp_port))

        for addr in targets:
            try:
                self._sock.sendto(frame, addr)
            except OSError:
                pass

    @staticmethod
    def _get_arp_hotspot_peers() -> list[str]:
        """Discover active client IPs connected to Windows Mobile Hotspot (192.168.137.x) or P2P (192.168.49.x)."""
        peers: list[str] = []
        try:
            res = subprocess.run(
                ["arp", "-a"],
                capture_output=True,
                timeout=3.5,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            text = (res.stdout or b"").decode("ascii", errors="ignore")
            for line in text.splitlines():
                parts = line.split()
                if len(parts) >= 2:
                    ip = parts[0]
                    if (ip.startswith("192.168.137.") or ip.startswith("192.168.49.")) and not ip.endswith(".1") and not ip.endswith(".255"):
                        peers.append(ip)
        except Exception:
            pass
        return peers

    def _udp_receive_loop(self) -> None:
        while self._running and self._sock:
            try:
                data, (sender_ip, _sender_port) = self._sock.recvfrom(65535)
            except ConnectionResetError:
                # Ignore Windows ICMP Port Unreachable (WinError 10054)
                continue
            except OSError:
                if not self._running or self._sock is None:
                    break
                continue
            try:
                self._handle_raw_frame(data, sender_ip=sender_ip)
            except Exception as exc:
                logger.debug("Ignored malformed control packet from %s: %s", sender_ip, exc)

    def _handle_raw_frame(self, data: bytes, sender_ip: str) -> None:
        ftype, header, payload = decode_frame_from_bytes(data)
        remote_id = header.get("device_id", "")
        if not remote_id or remote_id == self.device_id:
            return

        target_id = header.get("target_device_id", "")
        if target_id and target_id != self.device_id:
            return

        remote_name = header.get("device_name", f"Peer-{sender_ip}")
        remote_platform = header.get("platform", "android")
        remote_data_port = int(header.get("data_port", DATA_TCP_PORT))

        with self._peers_lock:
            peer = self._peers.get(remote_id)
            is_new = peer is None
            changed = False
            if peer is None:
                peer = PeerInfo(
                    device_id=remote_id,
                    device_name=remote_name,
                    platform=remote_platform,
                    ip=sender_ip,
                    data_port=remote_data_port,
                    last_seen=time.time(),
                    p2p_ssid=header.get("ssid"),
                    p2p_passphrase=header.get("passphrase"),
                    p2p_go_ip=header.get("go_ip"),
                    discovery_source="lan",
                )
                self._peers[remote_id] = peer
            else:
                if peer.device_name != remote_name or peer.ip != sender_ip:
                    changed = True
                peer.device_name = remote_name
                if sender_ip and "." in sender_ip:
                    peer.ip = sender_ip
                if header.get("ssid"):
                    peer.p2p_ssid = header.get("ssid")
                if header.get("passphrase"):
                    peer.p2p_passphrase = header.get("passphrase")
                if header.get("go_ip"):
                    peer.p2p_go_ip = header.get("go_ip")
                peer.discovery_source = "lan"
                peer.last_seen = time.time()

            # Remove temporary scan placeholder if we now know the real peer
            if peer.p2p_ssid:
                self._peers.pop(f"p2p-{peer.p2p_ssid}", None)

        # A teardown must not first count as "trusted peer seen" and trigger an auto-reconnect.
        if ftype != FrameType.P2P_TEARDOWN and (is_new or changed or not peer.is_connected) and self.on_peer_discovered:
            self.on_peer_discovered(peer)

        if ftype == FrameType.HELLO:
            if not header.get("is_reply"):
                reply = encode_frame(FrameType.HELLO, self._hello_header(is_reply=True))
                if sender_ip and "." in sender_ip and self._sock:
                    try:
                        self._sock.sendto(reply, (sender_ip, self.udp_port))
                    except OSError:
                        pass

        elif ftype == FrameType.CLIPBOARD_SYNC:
            text = payload.decode("utf-8", errors="replace")
            clip_id = header.get("clip_id") or compute_clip_hash(payload)
            if self.on_clipboard_received:
                self.on_clipboard_received(text, clip_id, remote_name)

        elif ftype == FrameType.P2P_WAKE_REQUEST:
            if self.on_p2p_wake_request:
                self.on_p2p_wake_request(peer, header)

        elif ftype == FrameType.P2P_GROUP_READY:
            peer.p2p_ssid = header.get("ssid")
            peer.p2p_go_ip = header.get("go_ip")
            if self.on_p2p_group_ready:
                self.on_p2p_group_ready(peer, header)

        elif ftype == FrameType.P2P_TEARDOWN:
            if self.on_p2p_teardown:
                self.on_p2p_teardown(peer)

        elif ftype == FrameType.PAIR_REQUEST:
            if self.on_pair_request:
                self.on_pair_request(peer, header)

        elif ftype == FrameType.PAIR_RESPONSE:
            accepted = bool(header.get("accepted", False))
            if self.on_pair_response:
                self.on_pair_response(peer, accepted)
