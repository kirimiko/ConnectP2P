"""
End-to-End Automated Test Suite for ConnectP2P.

Verifies:
1. Wire protocol encoding/decoding & BLE MTU chunking/reassembly
2. Clipboard loop suppression (SHA-256 hash deduplication)
3. Low-power micro-clipboard sync over UDP control channel without waking Wi-Fi P2P
4. High-speed TCP file transfer with SHA-256 verification
5. Breakpoint resume (`resume_offset` recovery from partial `.part` file)
6. Automatic idle power state transition (`DEEP_IDLE` -> `P2P_BURST_ACTIVE` -> `DEEP_IDLE`
   after idle timeout with teardown callback)
"""

from __future__ import annotations

import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "windows"))

from clipboard_win32 import ClipboardSyncController
from control_channel import LowPowerControlChannel
from protocol import (
    BleChunkReassembler,
    FrameType,
    PowerState,
    chunk_ble_payload,
    compute_clip_hash,
    compute_file_sha256,
    decode_frame_from_bytes,
    decode_p2p_ssid,
    derive_p2p_passphrase,
    encode_frame,
    encode_p2p_ssid,
)
from transport import IdlePowerWatchdog, P2pDataTransportEngine


class ConnectP2pEndToEndTest(unittest.TestCase):
    def test_01_protocol_framing_and_ble_chunk_reassembly(self) -> None:
        payload = "你好 ConnectP2P! 跨平台低功耗剪贴板与Wi-Fi P2P测试 🚀".encode("utf-8") * 20
        raw_frame = encode_frame(
            FrameType.CLIPBOARD_SYNC,
            {
                "device_id": "and-pixel8",
                "device_name": "Pixel_8_Pro",
                "clip_id": compute_clip_hash(payload),
                "mime": "text/plain",
            },
            payload=payload,
        )

        # Split into BLE 480-byte chunks and reassemble
        chunks = chunk_ble_payload(raw_frame, mtu_chunk=120)
        self.assertGreater(len(chunks), 3)

        reassembler = BleChunkReassembler()
        assembled = None
        for ch in chunks:
            assembled = reassembler.feed(ch)
        self.assertIsNotNone(assembled)
        assert assembled is not None

        ftype, header, decoded_payload = decode_frame_from_bytes(assembled)
        self.assertEqual(ftype, FrameType.CLIPBOARD_SYNC)
        self.assertEqual(header["device_id"], "and-pixel8")
        self.assertEqual(decoded_payload, payload)

    def test_02_clipboard_loop_suppression(self) -> None:
        from clipboard_win32 import get_clipboard_text, set_clipboard_text

        original_clip = get_clipboard_text()
        if original_clip is not None:
            self.addCleanup(set_clipboard_text, original_clip)

        triggered = []
        ctrl = ClipboardSyncController(on_local_clipboard_change=lambda txt, h: triggered.append((txt, h)))

        sample_text = f"LoopSuppressionTest_{time.time()}"
        sample_hash = compute_clip_hash(sample_text.encode("utf-8"))

        # Simulate receiving remote clipboard
        applied_first = ctrl.apply_remote_clipboard(sample_text, remote_clip_hash=sample_hash)
        self.assertTrue(applied_first)

        # Applying identical remote clipboard hash again should be suppressed immediately
        applied_second = ctrl.apply_remote_clipboard(sample_text, remote_clip_hash=sample_hash)
        self.assertFalse(applied_second)

        # If OS fires WM_CLIPBOARDUPDATE right after remote write, check_and_notify_now() must suppress it
        res = ctrl.check_and_notify_now()
        self.assertIsNone(res)
        self.assertEqual(len(triggered), 0)

    def test_03_low_power_inline_clipboard_sync_without_p2p_wakeup(self) -> None:
        received_events = []
        done_evt = threading.Event()

        def on_clip(text: str, clip_id: str, sender: str) -> None:
            received_events.append((text, clip_id, sender))
            done_evt.set()

        # Bind receiver on an ephemeral UDP port
        receiver = LowPowerControlChannel(
            device_id="and-receiver",
            device_name="AndroidReceiver",
            platform="android",
            udp_port=45891,
            on_clipboard_received=on_clip,
        )
        sender = LowPowerControlChannel(
            device_id="win-sender",
            device_name="WindowsLaptop",
            platform="windows",
            udp_port=45892,
        )
        receiver.start()
        sender.start()
        try:
            # Send directly to receiver's port via internal frame send
            text = "这段文本 <= 16KB，直接通过低功耗微数据通道同步，无需唤醒 Wi-Fi P2P 热点！"
            payload = text.encode("utf-8")
            frame = encode_frame(
                FrameType.CLIPBOARD_SYNC,
                {
                    "device_id": sender.device_id,
                    "device_name": sender.device_name,
                    "clip_id": compute_clip_hash(payload),
                    "mime": "text/plain",
                },
                payload=payload,
            )
            assert sender._sock is not None
            sender._sock.sendto(frame, ("127.0.0.1", 45891))

            self.assertTrue(done_evt.wait(timeout=2.0))
            self.assertEqual(len(received_events), 1)
            self.assertEqual(received_events[0][0], text)
            self.assertEqual(received_events[0][2], "WindowsLaptop")
        finally:
            sender.stop()
            receiver.stop()

    def test_04_file_transfer_breakpoint_resume_and_idle_power_teardown(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            src_dir = tmp_path / "src"
            dst_dir = tmp_path / "dst"
            src_dir.mkdir()
            dst_dir.mkdir()

            # Create a 1.5 MB test file with random bytes
            src_file = src_dir / "dataset_test.bin"
            raw_content = os.urandom(1536 * 1024)
            src_file.write_bytes(raw_content)
            expected_sha = compute_file_sha256(str(src_file))
            file_id = expected_sha[:16]

            # Simulate a 512 KB interrupted partial file (.part) on receiver side to test breakpoint resume!
            part_file = dst_dir / f"dataset_test.bin.{file_id}.part"
            part_file.write_bytes(raw_content[: 512 * 1024])

            state_history = []
            teardown_called = threading.Event()

            watchdog = IdlePowerWatchdog(
                idle_timeout_sec=0.4,  # Fast 400ms idle timeout for test verification
                on_state_change=lambda st: state_history.append(st),
                on_idle_teardown=lambda: teardown_called.set(),
            )

            progress_records = []
            completed_files = []

            engine = P2pDataTransportEngine(
                device_id="and-peer-1",
                device_name="AndroidPhone1",
                download_dir=dst_dir,
                listen_port=0,  # OS-assigned free port
                watchdog=watchdog,
                on_progress=lambda p: progress_records.append(p),
                on_file_received=lambda path, sender, ok: completed_files.append((path, sender, ok)),
            )
            bound_port = engine.start_server()
            try:
                self.assertEqual(watchdog.state, PowerState.DEEP_IDLE)

                ok = engine.send_file("127.0.0.1", src_file, target_port=bound_port, file_id=file_id)
                self.assertTrue(ok)

                # Verify breakpoint resume actually resumed from 512 KB (524,288 bytes)
                send_progs = [p for p in progress_records if p.direction == "send"]
                self.assertGreater(len(send_progs), 0)
                self.assertEqual(send_progs[0].resumed_from, 512 * 1024)

                # Verify received file matches SHA-256
                self.assertEqual(len(completed_files), 1)
                saved_path, _sender, verified = completed_files[0]
                self.assertTrue(verified)
                self.assertEqual(compute_file_sha256(str(saved_path)), expected_sha)

                # Wait for automatic idle teardown (400ms)
                self.assertTrue(teardown_called.wait(timeout=2.0))
                self.assertEqual(watchdog.state, PowerState.DEEP_IDLE)
                self.assertIn(PowerState.P2P_BURST_ACTIVE, state_history)
                self.assertEqual(state_history[-1], PowerState.DEEP_IDLE)
            finally:
                engine.stop_server()

    def test_05_p2p_ssid_nickname_encoding_and_auto_passphrase(self) -> None:
        # 1. Pure ASCII nickname
        ssid_ascii = encode_p2p_ssid("AndroidPhonePro")
        self.assertTrue(ssid_ascii.startswith("DIRECT-CP-"))
        self.assertLessEqual(len(ssid_ascii.encode("utf-8")), 32)
        name_ascii, is_auto_ascii = decode_p2p_ssid(ssid_ascii)
        self.assertTrue(is_auto_ascii)
        self.assertEqual(name_ascii, "AndroidPhonePro")

        # 2. Chinese nickname (<= 3 Chinese chars / 9 UTF-8 bytes fits in 32-byte SSID)
        ssid_cn = encode_p2p_ssid("手机Pro")
        self.assertTrue(ssid_cn.startswith("DIRECT-CP-"))
        self.assertLessEqual(len(ssid_cn.encode("utf-8")), 32)
        name_cn, is_auto_cn = decode_p2p_ssid(ssid_cn)
        self.assertTrue(is_auto_cn)
        self.assertEqual(name_cn, "手机Pro")

        # 3. Deterministic WPA2 passphrase (8..63 chars)
        pwd = derive_p2p_passphrase(ssid_cn)
        self.assertEqual(len(pwd), 12)
        self.assertEqual(pwd, derive_p2p_passphrase(ssid_cn))

    def test_06_targeted_device_filtering_and_individual_disconnect(self) -> None:
        from coordinator import ConnectP2pWindowsNode
        from protocol import PeerInfo

        b_received: list[str] = []
        c_received: list[str] = []
        evt_b = threading.Event()

        peer_b_ch = LowPowerControlChannel(
            device_id="and-peer-B",
            device_name="PhoneB",
            platform="android",
            udp_port=45895,
            on_clipboard_received=lambda txt, cid, s: (b_received.append(txt), evt_b.set()),
        )
        peer_c_ch = LowPowerControlChannel(
            device_id="and-peer-C",
            device_name="PhoneC",
            platform="android",
            udp_port=45896,
            on_clipboard_received=lambda txt, cid, s: c_received.append(txt),
        )
        peer_b_ch.start()
        peer_c_ch.start()
        try:
            # Frame explicitly targeted at and-peer-B; even if delivered to peer_c_ch, peer_c_ch must ignore it
            payload = "仅发给PhoneB的定向消息".encode("utf-8")
            frame = encode_frame(
                FrameType.CLIPBOARD_SYNC,
                {
                    "device_id": "win-sender",
                    "device_name": "Laptop",
                    "target_device_id": "and-peer-B",
                    "clip_id": compute_clip_hash(payload),
                    "mime": "text/plain",
                },
                payload=payload,
            )
            peer_c_ch._handle_raw_frame(frame, sender_ip="127.0.0.1")
            peer_b_ch._handle_raw_frame(frame, sender_ip="127.0.0.1")

            self.assertTrue(evt_b.wait(timeout=1.0))
            self.assertEqual(len(b_received), 1)
            self.assertEqual(len(c_received), 0)

            # Verify individual peer disconnect keeps other connected peers intact
            node = ConnectP2pWindowsNode(device_name="TestWin", udp_port=45897, tcp_port=0, enable_ble=False)
            p1 = PeerInfo(device_id="and-1", device_name="Phone1", platform="android", ip="127.0.0.1", is_connected=True)
            p2 = PeerInfo(device_id="and-2", device_name="Phone2", platform="android", ip="127.0.0.2", is_connected=True)
            node.control._peers["and-1"] = p1
            node.control._peers["and-2"] = p2
            node.connected_peer = p1

            # Disconnect only p1; p2 must remain connected and automatically become the active target
            node.disconnect_peer(p1)
            self.assertFalse(p1.is_connected)
            self.assertTrue(p2.is_connected)
            self.assertIsNotNone(node.connected_peer)
            assert node.connected_peer is not None
            self.assertEqual(node.connected_peer.device_id, "and-2")
        finally:
            peer_b_ch.stop()
            peer_c_ch.stop()

    def test_07_application_layer_aes256_encryption_and_tamper_detection(self) -> None:
        secret_text = "TopSecretPassword_银行卡号_622202"
        payload = secret_text.encode("utf-8")
        frame = encode_frame(
            FrameType.CLIPBOARD_SYNC,
            {
                "device_id": "and-phone-01",
                "device_name": "MyAndroidPhone",
                "clip_id": compute_clip_hash(payload),
            },
            payload=payload,
        )
        # 1. Verify neither the header metadata nor the secret payload appears in plaintext on the wire
        self.assertNotIn(b"TopSecretPassword", frame)
        self.assertNotIn(b"and-phone-01", frame)
        self.assertEqual(frame[7:9], b"E1")  # AES-256-GCM encrypted header marker

        # 2. Verify legitimate decryption succeeds
        ftype, hdr, dec_payload = decode_frame_from_bytes(frame)
        self.assertEqual(ftype, FrameType.CLIPBOARD_SYNC)
        self.assertEqual(hdr["device_id"], "and-phone-01")
        self.assertEqual(dec_payload.decode("utf-8"), secret_text)

        # 3. Verify tampering with 1 byte of ciphertext triggers AES-GCM authentication failure
        tampered = bytearray(frame)
        tampered[-5] ^= 0xFF
        with self.assertRaises(ValueError):
            decode_frame_from_bytes(bytes(tampered))

    def test_08_stranger_device_pairing_consent_flow(self) -> None:
        from coordinator import ConnectP2pWindowsNode
        from protocol import PeerInfo

        prompted_peers: list[PeerInfo] = []
        prompt_evt = threading.Event()

        node = ConnectP2pWindowsNode(
            device_name="ConsentWinPC",
            udp_port=45898,
            tcp_port=0,
            enable_ble=False,
            on_pair_request_prompt=lambda p: (prompted_peers.append(p), prompt_evt.set()),
        )
        node.trusted_peers.clear()
        node.start()
        try:
            # 1. Discovering a stranger device must NOT auto-connect!
            stranger = PeerInfo(
                device_id="and-stranger-99",
                device_name="StrangerPhone",
                platform="android",
                ip="127.0.0.1",
                data_port=node.transport.bound_port,
            )
            node.control.upsert_discovered_peer(stranger)
            self.assertIsNone(node.connected_peer)
            self.assertFalse(stranger.is_connected)

            # 2. Unauthorized clipboard from stranger must be blocked before approval
            self.assertFalse(node._is_sender_authorized("and-stranger-99", "StrangerPhone", "127.0.0.1"))

            # 3. Incoming PAIR_REQUEST from stranger triggers manual approval prompt
            req_frame = encode_frame(
                FrameType.PAIR_REQUEST,
                {
                    "device_id": "and-stranger-99",
                    "device_name": "StrangerPhone",
                    "platform": "android",
                    "data_port": node.transport.bound_port,
                },
            )
            node.control._handle_raw_frame(req_frame, sender_ip="127.0.0.1")
            self.assertTrue(prompt_evt.wait(timeout=1.0))
            self.assertEqual(len(prompted_peers), 1)
            self.assertIsNone(node.connected_peer)

            # 4. User clicks "Approve" -> peer becomes trusted & connected
            node.respond_to_pair_request(prompted_peers[0], accepted=True)
            self.assertTrue(node.is_trusted_peer(stranger))
            self.assertIsNotNone(node.connected_peer)
            self.assertTrue(node._is_sender_authorized("and-stranger-99", "StrangerPhone", "127.0.0.1"))
        finally:
            node.stop()

    def test_09_liveness_probe_and_automatic_disconnect_detection(self) -> None:
        from coordinator import ConnectP2pWindowsNode
        from protocol import PeerInfo

        with tempfile.TemporaryDirectory() as tmpdir:
            peer_engine = P2pDataTransportEngine(
                device_id="and-live-peer",
                device_name="LivePhone",
                download_dir=Path(tmpdir),
                listen_port=0,
            )
            peer_port = peer_engine.start_server()
            node = ConnectP2pWindowsNode(device_name="MonitorPC", udp_port=45899, tcp_port=0, enable_ble=False)
            node.start()
            try:
                peer = PeerInfo(
                    device_id="and-live-peer",
                    device_name="LivePhone",
                    platform="android",
                    ip="127.0.0.1",
                    data_port=peer_port,
                    is_connected=True,
                )
                node.control._peers[peer.device_id] = peer
                node.connected_peer = peer

                # 1. While peer server is running, liveness check succeeds and keeps peer connected
                node.verify_connected_peers_liveness(force_probe=True)
                self.assertTrue(peer.is_connected)
                self.assertIsNotNone(node.connected_peer)

                # 2. Simulate peer going offline (e.g., turning off Wi-Fi or closing app)
                peer_engine.stop_server()
                node.verify_connected_peers_liveness(force_probe=True)
                self.assertFalse(peer.is_connected)
                self.assertIsNone(node.connected_peer)
            finally:
                peer_engine.stop_server()
                node.stop()


if __name__ == "__main__":
    unittest.main(verbosity=2)
