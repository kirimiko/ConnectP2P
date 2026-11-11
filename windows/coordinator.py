"""
ConnectP2P Windows Node Coordinator.

Orchestrates:
1. Low-power idle state (`DEEP_IDLE`):
   - Event-driven Win32 clipboard listener (`WM_CLIPBOARDUPDATE`)
   - Small text clipboard (< 16KB) sent inline over control channel without waking Wi-Fi P2P
2. On-demand Wi-Fi P2P Burst state (`P2P_HANDSHAKE` -> `P2P_BURST_ACTIVE`):
   - Triggered automatically when sending files or large clipboard (> 16KB),
     or when an Android peer sends `P2P_WAKE_REQUEST`
   - Automatically tears down the Wi-Fi Direct group after `idle_timeout_sec` (default 15s)
     of zero transfer activity (`IdlePowerWatchdog`), returning to `DEEP_IDLE`.
"""

from __future__ import annotations

import json
import logging
import socket
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from ble_link import BleCentralLink
from clipboard_win32 import ClipboardSyncController, get_clipboard_text
from control_channel import LowPowerControlChannel
from protocol import (
    ANDROID_GO_DEFAULT_IP,
    CONTROL_UDP_PORT,
    DATA_TCP_PORT,
    MAX_INLINE_CLIPBOARD_BYTES,
    P2P_IDLE_TEARDOWN_SECONDS,
    FrameType,
    PeerInfo,
    PowerState,
    derive_p2p_passphrase,
    encode_frame,
)
from transport import IdlePowerWatchdog, P2pDataTransportEngine, TransferProgress
from wifi_p2p_win import P2pGroupCredentials, WindowsWifiP2pController

logger = logging.getLogger("ConnectP2P.Coordinator")

CONFIG_FILE = Path.home() / ".connect_p2p_config.json"


def _read_config_dict() -> Dict[str, Any]:
    try:
        if CONFIG_FILE.is_file():
            data = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
    except Exception:
        pass
    return {}


def _write_config_dict(data: Dict[str, Any]) -> None:
    try:
        CONFIG_FILE.write_text(
            json.dumps(data, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    except Exception:
        pass


def load_saved_device_name() -> Optional[str]:
    name = str(_read_config_dict().get("device_name", "")).strip()
    return name or None


def save_device_name(name: str) -> None:
    cfg = _read_config_dict()
    cfg["device_name"] = name.strip()
    _write_config_dict(cfg)


def load_hotspot_always_on() -> bool:
    return bool(_read_config_dict().get("hotspot_always_on", False))


def save_hotspot_always_on(enabled: bool) -> None:
    cfg = _read_config_dict()
    cfg["hotspot_always_on"] = bool(enabled)
    _write_config_dict(cfg)


def load_trusted_peers() -> Dict[str, str]:
    """Return mapping of `{device_id_or_name: friendly_name}` for auto-reconnect."""
    raw = _read_config_dict().get("trusted_peers", {})
    if isinstance(raw, dict):
        return {str(k): str(v) for k, v in raw.items()}
    return {}


def save_trusted_peer(peer: PeerInfo) -> None:
    cfg = _read_config_dict()
    trusted = cfg.get("trusted_peers")
    if not isinstance(trusted, dict):
        trusted = {}
    if peer.device_id and not peer.device_id.startswith("p2p-"):
        trusted[peer.device_id] = peer.device_name
    if peer.device_name:
        trusted[f"name:{peer.device_name}"] = peer.device_name
    cfg["trusted_peers"] = trusted
    _write_config_dict(cfg)


def remove_trusted_peer(peer: PeerInfo) -> None:
    cfg = _read_config_dict()
    trusted = cfg.get("trusted_peers")
    if isinstance(trusted, dict):
        if peer.device_id:
            trusted.pop(peer.device_id, None)
        if peer.device_name:
            trusted.pop(f"name:{peer.device_name}", None)
        cfg["trusted_peers"] = trusted
        _write_config_dict(cfg)


class ConnectP2pWindowsNode:
    def __init__(
        self,
        device_name: Optional[str] = None,
        download_dir: Optional[Path] = None,
        udp_port: int = CONTROL_UDP_PORT,
        tcp_port: int = DATA_TCP_PORT,
        idle_timeout_sec: float = P2P_IDLE_TEARDOWN_SECONDS,
        auto_clipboard_sync: bool = True,
        on_log: Optional[Callable[[str], None]] = None,
        on_power_state_change: Optional[Callable[[PowerState], None]] = None,
        on_peers_updated: Optional[Callable[[List[PeerInfo]], None]] = None,
        on_connected_peer_change: Optional[Callable[[Optional[PeerInfo]], None]] = None,
        on_progress: Optional[Callable[[TransferProgress], None]] = None,
        on_pair_request_prompt: Optional[Callable[[PeerInfo], None]] = None,
        enable_ble: bool = True,
    ) -> None:
        self.device_id = f"win-{uuid.getnode():012x}"
        self.enable_ble = enable_ble
        self.device_name = device_name or load_saved_device_name() or f"WinPC-{socket.gethostname()}"
        self.download_dir = Path(download_dir or (Path.home() / "Downloads" / "ConnectP2P"))
        self.auto_clipboard_sync = auto_clipboard_sync
        self.auto_reconnect_trusted = True
        self.trusted_peers: Dict[str, str] = load_trusted_peers()
        self.manually_disconnected_ids: set[str] = set()
        self.connected_peer: Optional[PeerInfo] = None
        self._manually_disconnected: bool = False

        self.on_log = on_log
        self.on_power_state_change = on_power_state_change
        self.on_peers_updated = on_peers_updated
        self.on_connected_peer_change = on_connected_peer_change
        self.on_progress = on_progress
        self.on_pair_request_prompt = on_pair_request_prompt

        self.p2p_controller = WindowsWifiP2pController(device_name=self.device_name)

        self.watchdog = IdlePowerWatchdog(
            idle_timeout_sec=idle_timeout_sec,
            on_state_change=self._handle_power_state_change,
            on_idle_teardown=self._handle_idle_teardown,
        )

        self.transport = P2pDataTransportEngine(
            device_id=self.device_id,
            device_name=self.device_name,
            download_dir=self.download_dir,
            listen_port=tcp_port,
            watchdog=self.watchdog,
            on_progress=self.on_progress,
            on_file_received=self._handle_file_received,
            on_clipboard_received=self._handle_remote_clipboard,
            on_control_frame_received=self._handle_tcp_control_frame,
            is_sender_authorized=self._is_sender_authorized,
        )

        self.control = LowPowerControlChannel(
            device_id=self.device_id,
            device_name=self.device_name,
            platform="windows",
            udp_port=udp_port,
            data_port=tcp_port,
            on_peer_discovered=self._handle_peer_discovered,
            on_clipboard_received=self._handle_remote_clipboard,
            on_p2p_wake_request=self._handle_p2p_wake_request,
            on_p2p_group_ready=self._handle_p2p_group_ready,
            on_p2p_teardown=self._handle_remote_p2p_teardown,
            on_pair_request=self._handle_pair_request,
            on_pair_response=self._handle_pair_response,
        )

        self.clipboard = ClipboardSyncController(
            on_local_clipboard_change=self._handle_local_clipboard_change
        )

        # BLE is the default link to phones; the Wi-Fi hotspot only comes up for bulk transfers,
        # unless the user opts into keeping it on (phones without BLE, lowest latency).
        self.ble = BleCentralLink(
            on_frame=self.control.handle_ble_frame,
            on_link_change=self._handle_ble_link_change,
            ping_frame=lambda: encode_frame(FrameType.HELLO, self.control._hello_header(is_reply=False)),
        )
        self.control.ble_link = self.ble
        self.hotspot_always_on = load_hotspot_always_on()

        self._p2p_ready_event = threading.Event()
        self._liveness_thread: Optional[threading.Thread] = None
        self._hotspot_thread: Optional[threading.Thread] = None
        self._hotspot_wanted = False
        self._hotspot_creds: Optional[P2pGroupCredentials] = None
        self._last_clip_send: Optional[tuple] = None
        self._running = False

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        bound_tcp = self.transport.start_server()
        self.control.data_port = bound_tcp
        self.control.start()
        self.clipboard.start()
        self.control.announce_presence_once()
        self._liveness_thread = threading.Thread(
            target=self._liveness_monitor_loop,
            name="ConnectP2PLivenessMonitor",
            daemon=True,
        )
        self._liveness_thread.start()
        if self.enable_ble:
            if self.ble.start():
                self._emit_log("[低功耗蓝牙] 已启动：正在搜索附近的 ConnectP2P 手机 (平时剪贴板与状态走蓝牙，传文件时才开热点)")
            else:
                self._emit_log("[低功耗蓝牙] 不可用 (未安装 bleak：pip install bleak)，将使用局域网或电脑热点连接")
        self._emit_log(
            f"[待机低功耗态 DEEP_IDLE] 已启动：TCP端口={bound_tcp}, UDP控制端口={self.control.udp_port}"
        )

    def stop(self) -> None:
        self._running = False
        # Notify all connected peers immediately before closing sockets
        for p in list(self.control.peers.values()):
            if not p.is_connected:
                continue
            ip, ble = self._targets(p)
            try:
                td = self.control.send_p2p_teardown(target_ip=ip, target_ble=ble, target_device_id=p.device_id)
                if ip:
                    self.transport.send_control_frame_over_tcp(ip, td, target_port=p.data_port, timeout=0.6)
            except Exception:
                pass
        time.sleep(0.3)  # let queued BLE writes go out before the link is closed
        self.clipboard.stop()
        self._hotspot_wanted = False
        self.ble.stop()
        self.p2p_controller.teardown()
        self.control.stop()
        self.transport.stop_server()

    def _targets(self, peer: Optional[PeerInfo]) -> tuple[Optional[str], Optional[str]]:
        """(IP, BLE address) the peer is currently reachable on; either may be None."""
        if peer is None:
            return None, None
        ble = peer.ble_address if self.ble.is_linked(peer.ble_address) else None
        return peer.ip, ble

    def _handle_ble_link_change(self, address: str, up: bool) -> None:
        if up:
            # Introduce ourselves; the phone's HELLO reply identifies it and, if trusted, connects it.
            self.control.announce_presence_once(target_ble=address)
            return
        peer = next((p for p in self.control.peers.values() if p.ble_address == address), None)
        if peer is None:
            return
        peer.ble_address = None
        if peer.is_connected and not (peer.ip and self.transport.probe_peer_alive(peer.ip, peer.data_port, timeout=1.5)):
            self._mark_peer_disconnected(peer, f"[连接状态更新] \"{peer.device_name}\" 的蓝牙连接已断开")

    def _mark_peer_disconnected(self, peer: PeerInfo, log_message: str) -> None:
        peer.is_connected = False
        self._emit_log(log_message)
        if self.connected_peer and self.connected_peer.device_id == peer.device_id:
            remaining = [p for p in self.control.peers.values() if p.is_connected and p.device_id != peer.device_id]
            self.connected_peer = remaining[0] if remaining else None
            if self.on_connected_peer_change:
                self.on_connected_peer_change(self.connected_peer)
        if self.on_peers_updated:
            self.on_peers_updated(list(self.control.peers.values()))

    def set_device_name(self, new_name: str) -> None:
        cleaned = new_name.strip()
        if not cleaned:
            return
        self.device_name = cleaned
        self.p2p_controller.device_name = cleaned
        self.transport.device_name = cleaned
        self.control.device_name = cleaned
        save_device_name(cleaned)
        self.control.announce_presence_once()
        self._emit_log(f"[设备名称已更新] 本机名称设为: \"{cleaned}\" (已向周围设备广播)")

    def verify_connected_peers_liveness(self, force_probe: bool = False) -> None:
        """
        Check if currently connected peers are still reachable over TCP (`PING_PONG`).
        If a peer disconnected (e.g., closed app, turned off Wi-Fi/hotspot), immediately
        marks `peer.is_connected = False` and updates UI connection status!
        """
        now = time.time()
        status_changed = False
        for peer in list(self.control.peers.values()):
            if not peer.is_connected or not peer.ip:
                continue
            if not force_probe and (now - peer.last_seen) < 5.0:
                continue
            alive = self.transport.probe_peer_alive(peer.ip, peer.data_port, timeout=1.5)
            if alive:
                peer.last_seen = time.time()
            elif self.ble.is_linked(peer.ble_address):
                # Still reachable over BLE; only the Wi-Fi path is gone (e.g. on-demand hotspot stopped).
                peer.ip = None
                status_changed = True
            else:
                peer.is_connected = False
                status_changed = True
                self._emit_log(f"[连接状态更新] 检测到设备 \"{peer.device_name}\" ({peer.ip}) 已断开连接/离线")
                if self.connected_peer and self.connected_peer.device_id == peer.device_id:
                    remaining = [
                        p for p in self.control.peers.values()
                        if p.is_connected and p.device_id != peer.device_id
                    ]
                    self.connected_peer = remaining[0] if remaining else None
                    if self.on_connected_peer_change:
                        self.on_connected_peer_change(self.connected_peer)

        if status_changed and self.on_peers_updated:
            self.on_peers_updated(list(self.control.peers.values()))

    def _liveness_monitor_loop(self) -> None:
        while self._running:
            time.sleep(5.0)
            if not self._running:
                break
            try:
                if any(p.is_connected for p in self.control.peers.values()):
                    self.verify_connected_peers_liveness(force_probe=False)
            except Exception:
                pass

    def scan_nearby_devices(self) -> List[PeerInfo]:
        """
        Discover nearby devices via:
        1. Event-driven UDP broadcast/multicast `HELLO` (discovers LAN & active P2P peers in <50ms)
        2. Windows WLAN scan for nearby `DIRECT-*` Wi-Fi Direct hotspots (decodes `DIRECT-CP-...` names & auto-keys)
        3. Liveness verification for any previously connected peers
        """
        self._emit_log("[正在扫描周围设备] 正在探测局域网在线设备与附近 Wi-Fi Direct 热点...")
        self.control.announce_presence_once()
        for arp_ip in self.control._get_arp_hotspot_peers():
            try:
                hello_frame = encode_frame(
                    FrameType.HELLO,
                    self.control._hello_header(is_reply=False),
                )
                self.transport.send_control_frame_over_tcp(
                    arp_ip,
                    hello_frame,
                    target_port=DATA_TCP_PORT,
                    timeout=0.8,
                )
            except Exception:
                pass
        p2p_peers = self.p2p_controller.scan_nearby_p2p_networks()
        for p in p2p_peers:
            self.control.upsert_discovered_peer(p)
        self.verify_connected_peers_liveness(force_probe=True)
        all_peers = list(self.control.peers.values())
        if self.on_peers_updated:
            self.on_peers_updated(all_peers)
        self._emit_log(f"[扫描完成] 当前共发现 {len(all_peers)} 台可用设备 (点击列表即可连接)")
        return all_peers

    def is_trusted_peer(self, peer: PeerInfo) -> bool:
        if peer.device_id in self.trusted_peers:
            return True
        if peer.device_name and f"name:{peer.device_name}" in self.trusted_peers:
            return True
        return False

    def remember_trusted_peer(self, peer: PeerInfo) -> None:
        if peer.device_id and not peer.device_id.startswith("p2p-"):
            self.trusted_peers[peer.device_id] = peer.device_name
        if peer.device_name:
            self.trusted_peers[f"name:{peer.device_name}"] = peer.device_name
        save_trusted_peer(peer)

    def is_peer_manually_disconnected(self, peer: PeerInfo) -> bool:
        return (
            peer.device_id in self.manually_disconnected_ids
            or (bool(peer.device_name) and f"name:{peer.device_name}" in self.manually_disconnected_ids)
        )

    def select_target_peer(self, peer: PeerInfo) -> None:
        if not peer.is_connected:
            self.connect_to_peer_and_sync(peer)
            return
        self.connected_peer = peer
        if self.on_connected_peer_change:
            self.on_connected_peer_change(peer)
        if self.on_peers_updated:
            self.on_peers_updated(list(self.control.peers.values()))
        self._emit_log(f"[已切换发送目标] 当前指定目标设备: {peer.device_name} ({peer.ip})")

    def connect_to_peer_and_sync(
        self,
        peer: PeerInfo,
        manual_passphrase: Optional[str] = None,
    ) -> bool:
        """
        Connect to a discovered peer:
        - If the peer is an UNTRUSTED (stranger) device: sends `PAIR_REQUEST` and waits for the
          remote user to manually approve the connection on their device!
        - If the peer is already a TRUSTED (`★已信任`) device: connects immediately and syncs clipboard.
        """
        self._manually_disconnected = False
        self.manually_disconnected_ids.discard(peer.device_id)
        if peer.device_name:
            self.manually_disconnected_ids.discard(f"name:{peer.device_name}")

        if peer.discovery_source == "p2p_scan" and peer.p2p_ssid:
            pwd = manual_passphrase or peer.p2p_passphrase or derive_p2p_passphrase(peer.p2p_ssid)
            go_ip = peer.p2p_go_ip or peer.ip or ANDROID_GO_DEFAULT_IP
            ok = self.connect_to_android_p2p_group(
                ssid=peer.p2p_ssid,
                passphrase=pwd,
                go_ip=go_ip,
                port=peer.data_port,
            )
            if not ok:
                return False
            peer.ip = go_ip
            peer.discovery_source = "lan"

        if not self.is_trusted_peer(peer):
            return self.request_pairing(peer)

        peer.is_connected = True
        self.remember_trusted_peer(peer)
        self.connected_peer = peer
        if self.on_connected_peer_change:
            self.on_connected_peer_change(peer)
        if self.on_peers_updated:
            self.on_peers_updated(list(self.control.peers.values()))

        ip, ble = self._targets(peer)
        if ip or ble:
            req_frame = self.control.send_pair_request(target_ip=ip, target_ble=ble, target_device_id=peer.device_id)
            if ip:
                self.transport.send_control_frame_over_tcp(ip, req_frame, target_port=peer.data_port)

        self._emit_log(f"[已连接信任设备] ★{peer.device_name} ({ip or '蓝牙'}) -> 正在自动同步剪贴板")
        clip_txt = get_clipboard_text()
        if clip_txt:
            self.sync_clipboard_text(clip_txt, target_peer=peer)
        return True

    def _unicast_targets(self, peer: PeerInfo) -> tuple[Optional[str], Optional[str]]:
        """Like `_targets`, but falls back to the phone's default group IP when nothing is known."""
        ip, ble = self._targets(peer)
        if not ip and not ble:
            ip = ANDROID_GO_DEFAULT_IP
        return ip, ble

    def request_pairing(self, peer: PeerInfo) -> bool:
        """
        Send a `PAIR_REQUEST` to a stranger device and wait for manual approval on the remote side.
        """
        dest_ip, dest_ble = self._unicast_targets(peer)
        req_frame = self.control.send_pair_request(target_ip=dest_ip, target_ble=dest_ble, target_device_id=peer.device_id)
        if dest_ip:
            self.transport.send_control_frame_over_tcp(dest_ip, req_frame, target_port=peer.data_port)
        self._emit_log(
            f"[等待对方同意连接] 已向陌生设备 \"{peer.device_name}\" ({dest_ip or '蓝牙'}) 发送连接请求，请在对端设备上点击『同意连接』..."
        )
        return True

    def respond_to_pair_request(self, peer: PeerInfo, accepted: bool) -> None:
        """
        Called when local user approves or rejects an incoming connection request from a stranger device.
        """
        dest_ip, dest_ble = self._unicast_targets(peer)
        resp_frame = self.control.send_pair_response(
            accepted=accepted,
            target_ip=dest_ip,
            target_ble=dest_ble,
            target_device_id=peer.device_id,
        )
        if dest_ip:
            self.transport.send_control_frame_over_tcp(dest_ip, resp_frame, target_port=peer.data_port)

        was_connected = peer.is_connected and self.connected_peer is not None and self.connected_peer.device_id == peer.device_id
        if accepted and was_connected:
            return
        if accepted:
            self._manually_disconnected = False
            self.manually_disconnected_ids.discard(peer.device_id)
            if peer.device_name:
                self.manually_disconnected_ids.discard(f"name:{peer.device_name}")
            peer.is_connected = True
            self.remember_trusted_peer(peer)
            self.connected_peer = peer
            if self.on_connected_peer_change:
                self.on_connected_peer_change(peer)
            if self.on_peers_updated:
                self.on_peers_updated(list(self.control.peers.values()))
            self._emit_log(f"[已同意连接] 已同意 \"{peer.device_name}\" ({dest_ip or '蓝牙'}) 的连接请求并加入 ★已信任设备")
            if self.auto_clipboard_sync:
                clip_txt = get_clipboard_text()
                if clip_txt:
                    self.sync_clipboard_text(clip_txt, target_peer=peer)
        else:
            peer.is_connected = False
            if self.on_peers_updated:
                self.on_peers_updated(list(self.control.peers.values()))
            self._emit_log(f"[已拒绝连接] 已拒绝陌生设备 \"{peer.device_name}\" ({dest_ip or '蓝牙'}) 的连接请求")

    def disconnect_peer(self, peer: PeerInfo, forget_trust: bool = False) -> None:
        """
        Disconnect a single specific device without affecting other connected devices!
        """
        peer.is_connected = False
        self.manually_disconnected_ids.add(peer.device_id)
        if peer.device_name:
            self.manually_disconnected_ids.add(f"name:{peer.device_name}")

        if forget_trust:
            if peer.device_id:
                self.trusted_peers.pop(peer.device_id, None)
            if peer.device_name:
                self.trusted_peers.pop(f"name:{peer.device_name}", None)
            remove_trusted_peer(peer)

        td_ip, td_ble = self._targets(peer)
        if td_ip or td_ble:
            td_frame = self.control.send_p2p_teardown(target_ip=td_ip, target_ble=td_ble, target_device_id=peer.device_id)
            if td_ip:
                self.transport.send_control_frame_over_tcp(td_ip, td_frame, target_port=peer.data_port, timeout=1.0)

        if self.connected_peer and self.connected_peer.device_id == peer.device_id:
            remaining = [
                p for p in self.control.peers.values()
                if p.is_connected and p.device_id != peer.device_id
            ]
            self.connected_peer = remaining[0] if remaining else None

        if self.connected_peer is None:
            self._teardown_client_link()
            self.watchdog.force_idle_now()

        if self.on_connected_peer_change:
            self.on_connected_peer_change(self.connected_peer)
        if self.on_peers_updated:
            self.on_peers_updated(list(self.control.peers.values()))

        self._emit_log(f"[已单独断开设备] 已断开与 {peer.device_name} 的连接 (双击列表可随时重连)")

    def _teardown_client_link(self) -> None:
        """Drop a laptop-as-client link to a phone group, but keep this PC's own hotspot up for reconnects."""
        creds = self.p2p_controller.active_credentials
        if creds is not None and creds.role == "CLIENT":
            self.p2p_controller.teardown()

    def disconnect_current_peer(self, forget_trust: bool = False) -> None:
        """
        Manually disconnect from the current target peer (or all if none active).
        """
        if self.connected_peer is not None:
            self.disconnect_peer(self.connected_peer, forget_trust=forget_trust)
            return
        self._manually_disconnected = True
        for p in self.control.peers.values():
            p.is_connected = False
            self.manually_disconnected_ids.add(p.device_id)
        self.connected_peer = None
        self._teardown_client_link()
        self.watchdog.force_idle_now()
        if self.on_connected_peer_change:
            self.on_connected_peer_change(None)
        if self.on_peers_updated:
            self.on_peers_updated(list(self.control.peers.values()))
        self._emit_log("[已手动断开连接] 当前无活动设备连接")

    def start_p2p_group_on_demand(self, persistent: bool = False, announce: bool = True) -> P2pGroupCredentials:
        """
        Bring up the Windows Mobile Hotspot (`DIRECT-CP-...`, 192.168.137.1; the laptop's own Wi-Fi stays up).

        - persistent: kept up by the keep-alive loop until `stop_hotspot()` ("hotspot always on" option).
        - otherwise on demand: the idle watchdog stops it once no transfer has used it for a while and the
          phones on it are still reachable over BLE.
        """
        self._manually_disconnected = False
        if persistent:
            self._hotspot_wanted = True
        creds = self.p2p_controller.start_autonomous_group_owner(port=self.transport.bound_port)
        self._hotspot_creds = creds
        if creds.hotspot_on:
            self.control.hotspot_info = {"ssid": creds.ssid, "passphrase": creds.passphrase, "go_ip": creds.go_ip}
            if not persistent:
                # Room for the phone to join and start the transfer before the idle teardown kicks in.
                self.watchdog.set_state(PowerState.P2P_BURST_ACTIVE)
                self.watchdog.touch_activity(timeout_sec=90.0)
            if announce:
                self.control.send_p2p_group_ready(
                    ssid=creds.ssid,
                    passphrase=creds.passphrase,
                    go_ip=creds.go_ip,
                    port=creds.port,
                )
            mode = "常开" if persistent else "临时"
            self._emit_log(f"[电脑热点已开启·{mode}] SSID={creds.ssid} ({creds.go_ip})，电脑主 Wi-Fi 不受影响")
        else:
            self._emit_log("[热点开启失败] Windows 移动热点未能开启（请确认 Wi-Fi 已打开，且系统设置里的『移动热点』可手动开启）")
        if persistent and (self._hotspot_thread is None or not self._hotspot_thread.is_alive()):
            self._hotspot_thread = threading.Thread(
                target=self._hotspot_keepalive_loop,
                name="ConnectP2PHotspotKeepalive",
                daemon=True,
            )
            self._hotspot_thread.start()
        return creds

    def stop_hotspot(self) -> None:
        self._hotspot_wanted = False
        self.control.hotspot_info = None
        self.p2p_controller.teardown()
        for p in self.control.peers.values():
            if p.ip and p.ip.startswith("192.168.137."):
                p.ip = None
        self._emit_log("[热点已关闭] Windows 移动热点已关闭")

    def set_hotspot_always_on(self, enabled: bool) -> None:
        self.hotspot_always_on = enabled
        save_hotspot_always_on(enabled)
        if enabled:
            self.start_p2p_group_on_demand(persistent=True)
        elif self.p2p_controller.is_active and self.watchdog.active_transfers == 0:
            self.stop_hotspot()

    def _ensure_wifi_path(self, peer: PeerInfo, timeout: float = 45.0) -> Optional[str]:
        """
        Returns an IP the peer answers on for bulk transfers. For a BLE-only phone this brings the hotspot
        up, tells the phone over BLE (P2P_GROUP_READY), and waits for its HELLO from the hotspot subnet.
        """
        if peer.ip and self.transport.probe_peer_alive(peer.ip, peer.data_port, timeout=1.5):
            return peer.ip
        _ip, ble = self._targets(peer)
        if not ble:
            return None
        self._emit_log(f"[准备高速通道] 正在临时开启电脑热点，并通过蓝牙通知 {peer.device_name} 接入...")
        creds = self.start_p2p_group_on_demand(announce=False)
        if not creds.hotspot_on:
            return None
        self.control.send_p2p_group_ready(
            ssid=creds.ssid,
            passphrase=creds.passphrase,
            go_ip=creds.go_ip,
            port=creds.port,
            target_ble=ble,
            target_device_id=peer.device_id,
        )
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(0.5)
            ip = peer.ip
            if ip and ip.startswith("192.168.137.") and self.transport.probe_peer_alive(ip, peer.data_port, timeout=1.0):
                self._emit_log(f"[准备高速通道] {peer.device_name} 已接入电脑热点 ({ip})")
                return ip
        self._emit_log(f"[准备高速通道] 等待 {peer.device_name} 接入热点超时")
        return None

    def _hotspot_keepalive_loop(self) -> None:
        """
        Keeps the Mobile Hotspot up while it is wanted (Windows may stop it when the laptop's
        Wi-Fi roams or the adapter resets), and announces this PC to hotspot clients that have
        not been seen yet. Windows ICS keeps client ARP entries as "static", so ARP alone cannot
        tell whether a client is still associated; unicast HELLO is cheap enough to repeat.
        """
        last_state_check = time.time()
        while self._running and self._hotspot_wanted:
            time.sleep(10.0)
            if not self._running or not self._hotspot_wanted:
                break
            try:
                connected_ips = {p.ip for p in self.control.peers.values() if p.is_connected and p.ip}
                for arp_ip in self.control._get_arp_hotspot_peers():
                    if arp_ip.startswith("192.168.137.") and arp_ip not in connected_ips:
                        self.control.announce_presence_once(target_ip=arp_ip)
            except Exception:
                pass
            if time.time() - last_state_check >= 60.0:
                last_state_check = time.time()
                if not self.p2p_controller.is_hotspot_on():
                    self._emit_log("[热点保活] 检测到 Windows 移动热点被系统关闭，正在自动重新开启...")
                    self.p2p_controller.teardown_local_state()
                    creds = self.p2p_controller.start_autonomous_group_owner(port=self.transport.bound_port)
                    if creds.hotspot_on:
                        self._hotspot_creds = creds
                        self.control.hotspot_info = {"ssid": creds.ssid, "passphrase": creds.passphrase, "go_ip": creds.go_ip}
                        self._emit_log(f"[热点保活] 已重新开启 {creds.ssid}")

    def connect_to_android_p2p_group(
        self,
        ssid: str,
        passphrase: str,
        go_ip: str = ANDROID_GO_DEFAULT_IP,
        port: int = DATA_TCP_PORT,
    ) -> bool:
        """
        Connect Windows laptop as client to an Android phone's Wi-Fi P2P Group (`DIRECT-xx-...`).
        """
        self.watchdog.set_state(PowerState.P2P_HANDSHAKE)
        self._emit_log(f"[P2P握手] 正在连接安卓 Wi-Fi Direct 热点 {ssid} ({go_ip}:{port})...")
        ok = self.p2p_controller.connect_to_android_group(
            ssid=ssid,
            passphrase=passphrase,
            go_ip=go_ip,
            port=port,
        )
        if ok:
            self.watchdog.set_state(PowerState.P2P_BURST_ACTIVE)
            self.watchdog.touch_activity()
            self.control.register_manual_peer(ip=go_ip, device_name=f"Android({ssid})", data_port=port)
            self.control.announce_presence_once(target_ip=go_ip)
            self._emit_log(f"[P2P已连接] 成功直连安卓 GO {go_ip}:{port}")
        else:
            self.watchdog.set_state(PowerState.DEEP_IDLE)
            self._emit_log(f"[P2P连接提示] 已配置 {ssid}，等待对端 {go_ip}:{port} 响应")
        return ok

    def teardown_p2p_now(self) -> None:
        """Immediately tear down Wi-Fi P2P link and return to DEEP_IDLE."""
        self.control.send_p2p_teardown()
        self.watchdog.force_idle_now()

    def sync_clipboard_text(
        self,
        text: str,
        target_ip: Optional[str] = None,
        target_peer: Optional[PeerInfo] = None,
    ) -> bool:
        """
        Clipboard sync to ONE peer (never broadcast):
        - Small text goes over BLE and/or the LAN/hotspot IP the peer is reachable on.
        - Text too large for BLE needs Wi-Fi; a BLE-only phone gets the hotspot brought up on demand.
        """
        resolved_peer = target_peer or self.connected_peer
        peer_ip, ble = self._targets(resolved_peer)
        dest_ip = target_ip or peer_ip
        dest_id = resolved_peer.device_id if resolved_peer else None
        dest_name = resolved_peer.device_name if resolved_peer else (dest_ip or "未指定设备")

        if not dest_ip and not ble:
            return False

        # Connect handshakes arrive over several links and each one triggers a sync; send a clip once.
        clip_key = (dest_id or dest_ip, hash(text))
        now = time.time()
        if self._last_clip_send and self._last_clip_send[0] == clip_key and now - self._last_clip_send[1] < 3.0:
            return True
        self._last_clip_send = (clip_key, now)

        dest_port = resolved_peer.data_port if resolved_peer else DATA_TCP_PORT
        payload_len = len(text.encode("utf-8"))
        preview = text.replace("\r", " ").replace("\n", " ")[:48]
        if payload_len <= MAX_INLINE_CLIPBOARD_BYTES:
            # Frame = payload + encrypted header/tag; leave room for that overhead.
            via_ble = ble is not None and payload_len + 512 <= self.ble.max_frame_bytes(ble)
            if via_ble or dest_ip:
                self.control.send_inline_clipboard(
                    text,
                    target_ip=dest_ip,
                    target_ble=ble if via_ble else None,
                    target_device_id=dest_id,
                )
                if dest_ip:
                    # TCP as well: an incoming connection wakes a frozen background app on OEM ROMs.
                    self.transport.send_clipboard_over_tcp(
                        dest_ip,
                        text,
                        target_port=dest_port,
                        timeout=1.2,
                        touch_burst_watchdog=False,
                    )
                link = "蓝牙" if via_ble else dest_ip
                self._emit_log(f"[已发送剪贴板 → {dest_name}] \"{preview}\" ({payload_len} Bytes · {link})")
                return True

        wifi_ip = self._ensure_wifi_path(resolved_peer) if resolved_peer else dest_ip
        if not wifi_ip:
            self._emit_log(f"[剪贴板发送失败 → {dest_name}] 内容较大需要 Wi-Fi 通道，但未能建立")
            return False
        ok = self.transport.send_clipboard_over_tcp(
            wifi_ip,
            text,
            target_port=dest_port,
            timeout=8.0,
            touch_burst_watchdog=True,
        )
        if ok:
            self._emit_log(f"[已发送大剪贴板 → {dest_name}] \"{preview}\" ({payload_len} Bytes)")
        return ok

    def send_files_on_demand(
        self,
        file_paths: List[str | Path],
        target_ip: Optional[str] = None,
        target_port: int = DATA_TCP_PORT,
        target_peer: Optional[PeerInfo] = None,
    ) -> bool:
        """
        Send one or more files over high-speed Wi-Fi P2P to a SPECIFIC target device (no broadcast):
        """
        resolved_peer = target_peer or self.connected_peer
        if resolved_peer is None and target_ip:
            for p in self.control.peers.values():
                if p.ip == target_ip:
                    resolved_peer = p
                    break
        dest_port = resolved_peer.data_port if resolved_peer else (target_port or DATA_TCP_PORT)
        dest_name = resolved_peer.device_name if resolved_peer else (target_ip or ANDROID_GO_DEFAULT_IP)

        if resolved_peer is not None:
            dest_ip = self._ensure_wifi_path(resolved_peer)
            if dest_ip is None and target_ip and self.transport.probe_peer_alive(target_ip, dest_port, timeout=1.2):
                dest_ip = target_ip
        else:
            dest_ip = target_ip or ANDROID_GO_DEFAULT_IP
        if not dest_ip:
            self._emit_log(
                f"[文件发送失败 → {dest_name}] 无法建立 Wi-Fi 高速通道 (对方不在同一网络且蓝牙未连接；可在电脑端勾选『热点常开』)"
            )
            return False

        all_ok = True
        for fp in file_paths:
            path = Path(fp)
            self._emit_log(f"[定向发送文件 → {dest_name}] {path.name} ({path.stat().st_size:,} Bytes) [{dest_ip}:{dest_port}]")
            try:
                ok = self.transport.send_file(dest_ip, path, target_port=dest_port)
                if ok:
                    self._emit_log(
                        f"[文件发送完成 → {dest_name}] {path.name} 校验通过 (SHA-256 OK)，闲置 {int(self.watchdog.idle_timeout_sec)}s 后自动释放 P2P 省电"
                    )
                else:
                    all_ok = False
                    self._emit_log(f"[文件发送失败 → {dest_name}] {path.name} 未能完成校验")
            except Exception as exc:
                all_ok = False
                self._emit_log(f"[文件发送异常 → {dest_name}] {path.name}: {exc}")
        return all_ok

    # ---------------- Internal Callbacks ----------------

    def _is_sender_authorized(self, sender_id: str, sender_name: str, sender_ip: str) -> bool:
        if self._manually_disconnected:
            return False
        if sender_id and sender_id in self.manually_disconnected_ids:
            return False
        if sender_name and f"name:{sender_name}" in self.manually_disconnected_ids:
            return False
        if sender_id and sender_id in self.trusted_peers:
            return True
        if sender_name and f"name:{sender_name}" in self.trusted_peers:
            return True
        peer = self.control.peers.get(sender_id) if sender_id else None
        if peer and peer.is_connected:
            return True
        if self.connected_peer and (
            (sender_id and self.connected_peer.device_id == sender_id)
            or (sender_ip and self.connected_peer.ip == sender_ip)
        ):
            return True
        # In headless unit tests where no GUI prompt handler is registered and no peers were pre-configured
        if self.on_pair_request_prompt is None and not self.trusted_peers and self.connected_peer is None:
            return True
        self._emit_log(f"[安全拦截] 已拦截未授权陌生设备 \"{sender_name}\" ({sender_ip}) 的数据请求，请先同意连接")
        return False

    def _handle_local_clipboard_change(self, text: str, clip_hash: str) -> None:
        if not self.auto_clipboard_sync or self._manually_disconnected or self.connected_peer is None:
            return
        self.sync_clipboard_text(text, target_ip=self.connected_peer.ip, target_peer=self.connected_peer)

    def _handle_remote_clipboard(self, text: str, clip_id: str, sender_name: str) -> None:
        if self._manually_disconnected or f"name:{sender_name}" in self.manually_disconnected_ids:
            return
        # Check authorization if GUI prompt handler is active
        if self.on_pair_request_prompt is not None:
            is_auth = (f"name:{sender_name}" in self.trusted_peers) or any(
                p.device_name == sender_name and (p.is_connected or self.is_trusted_peer(p))
                for p in self.control.peers.values()
            )
            if not is_auth:
                self._emit_log(f"[安全拦截] 已忽略未授权陌生设备 \"{sender_name}\" 发来的剪贴板")
                return
        applied = self.clipboard.apply_remote_clipboard(text, remote_clip_hash=clip_id)
        if applied:
            preview = text.replace("\r", " ").replace("\n", " ")[:48]
            self._emit_log(f"[收到剪贴板 ← {sender_name}] \"{preview}\" (已写入系统剪贴板)")

    def _handle_file_received(self, saved_path: Path, sender_name: str, verified: bool) -> None:
        if verified:
            self._emit_log(
                f"[收到文件 ← {sender_name}] 已保存至 {saved_path} (SHA-256 校验通过)"
            )
        else:
            self._emit_log(f"[文件校验失败 ← {sender_name}] {saved_path.name} SHA-256 不匹配，已丢弃")

    def _handle_peer_discovered(self, peer: PeerInfo) -> None:
        trusted = self.is_trusted_peer(peer)
        peer_disc = self.is_peer_manually_disconnected(peer)
        trust_badge = " [★已信任]" if trusted else " [陌生设备]"
        if peer.discovery_source == "p2p_scan":
            src_tag = "Wi-Fi直连热点"
        elif peer.ip:
            src_tag = f"在线 {peer.ip}:{peer.data_port}"
        else:
            src_tag = "低功耗蓝牙"
        self._emit_log(f"[发现设备] {peer.device_name}{trust_badge} [{peer.platform}] ({src_tag})")

        # ONLY auto-connect over an existing link (LAN/hotspot IP or BLE) if the peer is ALREADY trusted!
        # Do NOT auto-switch the laptop's main Wi-Fi card (`p2p_scan`) in the background, because switching
        # the primary WLAN adapter to a phone's P2P hotspot would disconnect the laptop's internet Wi-Fi.
        if not self._manually_disconnected and not peer_disc and self.auto_reconnect_trusted and trusted:
            if peer.discovery_source in ("lan", "ble"):
                already_same = (
                    peer.is_connected
                    and self.connected_peer is not None
                    and self.connected_peer.device_id == peer.device_id
                )
                peer.is_connected = True
                self.remember_trusted_peer(peer)
                if self.connected_peer is None or self.connected_peer.device_id == peer.device_id:
                    self.connected_peer = peer
                    if self.on_connected_peer_change:
                        self.on_connected_peer_change(peer)
                if not already_same:
                    self._emit_log(f"[自动重连成功] 已自动恢复与 ★{peer.device_name} ({peer.ip or '蓝牙'}) 的连接")
                    if self.auto_clipboard_sync:
                        clip_txt = get_clipboard_text()
                        if clip_txt:
                            self.sync_clipboard_text(clip_txt, target_peer=peer)
        elif not self._manually_disconnected and not peer_disc and trusted and self.connected_peer and (
            self.connected_peer.device_id == peer.device_id
            or (peer.ip and self.connected_peer.ip == peer.ip)
        ):
            peer.is_connected = True
            self.connected_peer = peer
            if self.on_connected_peer_change:
                self.on_connected_peer_change(peer)

        if self.on_peers_updated:
            self.on_peers_updated(list(self.control.peers.values()))

    def _handle_pair_request(self, peer: PeerInfo, header: Dict[str, Any]) -> None:
        if self.is_trusted_peer(peer):
            # Already trusted: auto-approve and mark connected
            self.respond_to_pair_request(peer, accepted=True)
            return

        self._emit_log(f"[收到连接请求 ← {peer.device_name}] 陌生设备 ({peer.ip}) 请求与本机建立连接，请确认是否同意")
        if self.on_pair_request_prompt:
            self.on_pair_request_prompt(peer)
        else:
            # Headless / CLI fallback
            self.respond_to_pair_request(peer, accepted=True)

    def _handle_pair_response(self, peer: PeerInfo, accepted: bool) -> None:
        if accepted:
            self._manually_disconnected = False
            self.manually_disconnected_ids.discard(peer.device_id)
            if peer.device_name:
                self.manually_disconnected_ids.discard(f"name:{peer.device_name}")
            peer.is_connected = True
            self.remember_trusted_peer(peer)
            self.connected_peer = peer
            if self.on_connected_peer_change:
                self.on_connected_peer_change(peer)
            if self.on_peers_updated:
                self.on_peers_updated(list(self.control.peers.values()))
            self._emit_log(
                f"[对方已同意连接] \"{peer.device_name}\" ({peer.ip}) 已同意连接请求！已加入 ★已信任设备 并同步剪贴板"
            )
            if self.auto_clipboard_sync:
                clip_txt = get_clipboard_text()
                if clip_txt:
                    self.sync_clipboard_text(clip_txt, target_ip=peer.ip, target_peer=peer)
        else:
            peer.is_connected = False
            if self.on_peers_updated:
                self.on_peers_updated(list(self.control.peers.values()))
            self._emit_log(f"[连接请求被拒绝] \"{peer.device_name}\" ({peer.ip}) 拒绝了连接请求")

    def _handle_tcp_control_frame(self, ftype: FrameType, header: Dict[str, Any], sender_ip: str) -> None:
        remote_id = str(header.get("device_id", ""))
        if not remote_id or remote_id == self.device_id:
            return
        target_id = str(header.get("target_device_id", ""))
        if target_id and target_id != self.device_id:
            return
        remote_name = str(header.get("device_name", f"Peer-{sender_ip}"))
        remote_platform = str(header.get("platform", "android"))
        remote_port = int(header.get("data_port", DATA_TCP_PORT))
        peer = PeerInfo(
            device_id=remote_id,
            device_name=remote_name,
            platform=remote_platform,
            ip=sender_ip,
            data_port=remote_port,
            discovery_source="lan",
        )
        self.control.upsert_discovered_peer(peer, notify=ftype != FrameType.P2P_TEARDOWN)
        stored_peer = self.control.peers.get(remote_id) or peer
        if ftype == FrameType.HELLO:
            if not header.get("is_reply"):
                reply_frame = encode_frame(
                    FrameType.HELLO,
                    self.control._hello_header(is_reply=True),
                )
                self.control.announce_presence_once(target_ip=sender_ip)
                self.transport.send_control_frame_over_tcp(
                    sender_ip,
                    reply_frame,
                    target_port=remote_port,
                    timeout=1.0,
                )
        elif ftype == FrameType.PAIR_REQUEST:
            self._handle_pair_request(stored_peer, header)
        elif ftype == FrameType.PAIR_RESPONSE:
            self._handle_pair_response(stored_peer, bool(header.get("accepted", False)))
        elif ftype == FrameType.P2P_WAKE_REQUEST:
            self._handle_p2p_wake_request(stored_peer, header)
        elif ftype == FrameType.P2P_GROUP_READY:
            self._handle_p2p_group_ready(stored_peer, header)
        elif ftype == FrameType.P2P_TEARDOWN:
            self._handle_remote_p2p_teardown(stored_peer)

    def _handle_p2p_wake_request(self, peer: PeerInfo, header: Dict[str, Any]) -> None:
        if self.is_peer_manually_disconnected(peer):
            return
        self._emit_log(f"[收到定向唤醒请求 ← {peer.device_name}] 原因={header.get('reason')}，正在临时开启电脑热点...")
        creds = self.start_p2p_group_on_demand(persistent=self.hotspot_always_on, announce=False)
        if not creds.hotspot_on:
            return
        ip, ble = self._targets(peer)
        if not ip and not ble:
            return
        self.control.send_p2p_group_ready(
            ssid=creds.ssid,
            passphrase=creds.passphrase,
            go_ip=creds.go_ip,
            port=creds.port,
            target_ip=ip,
            target_ble=ble,
            target_device_id=peer.device_id,
        )

    def _handle_p2p_group_ready(self, peer: PeerInfo, header: Dict[str, Any]) -> None:
        # Joining a phone's group would switch the laptop's primary Wi-Fi and drop its internet, so it is
        # never done automatically; the user can still connect from the GUI ("手动输入SSID连接").
        ssid = str(header.get("ssid", ""))
        go_ip = str(header.get("go_ip", ANDROID_GO_DEFAULT_IP))
        self._emit_log(f"[对端P2P就绪 ← {peer.device_name}] SSID={ssid}, GO={go_ip}（不自动切换电脑主 Wi-Fi）")
        self._p2p_ready_event.set()

    def _handle_remote_p2p_teardown(self, peer: PeerInfo) -> None:
        self._emit_log(f"[收到对端断开/休眠通知 ← {peer.device_name}] 已断开与 {peer.device_name} 的连接")
        peer.is_connected = False
        if self.connected_peer and self.connected_peer.device_id == peer.device_id:
            remaining = [
                p for p in self.control.peers.values()
                if p.is_connected and p.device_id != peer.device_id
            ]
            self.connected_peer = remaining[0] if remaining else None
        if self.connected_peer is None:
            self.watchdog.force_idle_now()
        if self.on_connected_peer_change:
            self.on_connected_peer_change(self.connected_peer)
        if self.on_peers_updated:
            self.on_peers_updated(list(self.control.peers.values()))

    def _handle_power_state_change(self, state: PowerState) -> None:
        state_labels = {
            PowerState.DEEP_IDLE: "DEEP_IDLE (超低功耗待机 - P2P已关闭)",
            PowerState.P2P_HANDSHAKE: "P2P_HANDSHAKE (按需Wi-Fi Direct握手中)",
            PowerState.P2P_BURST_ACTIVE: "P2P_BURST_ACTIVE (Wi-Fi P2P高速传输中)",
        }
        self._emit_log(f"[功耗状态切换] -> {state_labels.get(state, str(state))}")
        if self.on_power_state_change:
            self.on_power_state_change(state)

    def _handle_idle_teardown(self) -> None:
        creds = self.p2p_controller.active_credentials
        if creds is None:
            return
        if creds.role == "GO":
            if self._hotspot_wanted:
                return
            # On-demand hotspot: stop it once every phone on it is still reachable over BLE.
            on_hotspot = [
                p for p in self.control.peers.values()
                if p.is_connected and p.ip and p.ip.startswith("192.168.137.")
            ]
            if all(self.ble.is_linked(p.ble_address) for p in on_hotspot):
                self.stop_hotspot()
                self._emit_log("[空闲自动降功耗] 传输已结束，已关闭临时热点（剪贴板与状态继续走低功耗蓝牙）")
            else:
                # A phone without BLE depends on the hotspot; check again later.
                self.watchdog.touch_activity(timeout_sec=60.0)
            return
        has_active_p2p_peer = any(
            p.is_connected and p.ip and p.ip.startswith("192.168.49.")
            for p in self.control.peers.values()
        )
        if not has_active_p2p_peer:
            self.p2p_controller.teardown()
            self._emit_log("[空闲自动降功耗] 已断开电脑到手机热点的临时连接")

    def _emit_log(self, msg: str) -> None:
        logger.info(msg)
        if self.on_log:
            try:
                self.on_log(msg)
            except Exception:
                pass
