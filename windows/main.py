"""
ConnectP2P Windows Desktop Application (GUI + CLI).

Usage:
  1. Launch Desktop GUI:
     python windows/main.py

  2. Launch CLI mode:
     python windows/main.py --cli
     python windows/main.py --send-file path/to/file.zip --target 192.168.49.1
"""

from __future__ import annotations

import argparse
import logging
import queue
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import List, Optional

from coordinator import ConnectP2pWindowsNode
from protocol import ANDROID_GO_DEFAULT_IP, DATA_TCP_PORT, PeerInfo, PowerState
from transport import TransferProgress


class ConnectP2pDesktopApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("ConnectP2P - 低功耗 Wi-Fi P2P 文件传输与剪贴板同步 (Windows 端)")
        self.root.geometry("920x660")
        self.root.minsize(800, 560)

        self._ui_queue: "queue.Queue[ tuple[str, object] ]" = queue.Queue()
        self._displayed_peers: List[PeerInfo] = []

        self.node = ConnectP2pWindowsNode(
            on_log=lambda msg: self._ui_queue.put(("log", msg)),
            on_power_state_change=lambda st: self._ui_queue.put(("power", st)),
            on_peers_updated=lambda peers: self._ui_queue.put(("peers", peers)),
            on_connected_peer_change=lambda peer: self._ui_queue.put(("connected_peer", peer)),
            on_progress=lambda prog: self._ui_queue.put(("progress", prog)),
            on_pair_request_prompt=lambda peer: self._ui_queue.put(("pair_request", peer)),
        )

        self._build_ui()
        self.node.start()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.root.after(80, self._DrainUiQueue)
        # Automatically scan for nearby devices on startup; if not on same LAN, auto-enable virtual hotspot
        # (which keeps the laptop's main Wi-Fi 100% connected) so the phone can connect anytime!
        self.root.after(400, self._on_scan_nearby)
        self.root.after(1800, self._auto_start_go_if_not_connected)

    def _build_ui(self) -> None:
        style = ttk.Style()
        try:
            style.theme_use("vista")
        except Exception:
            pass

        # 0. Top Identity & Device Name Bar
        id_frame = ttk.Frame(self.root, padding=(12, 8, 12, 0))
        id_frame.pack(fill=tk.X)

        ttk.Label(id_frame, text="本机设备名称:", font=("Microsoft YaHei UI", 9, "bold")).pack(side=tk.LEFT)
        self.device_name_var = tk.StringVar(value=self.node.device_name)
        ttk.Entry(id_frame, textvariable=self.device_name_var, width=22).pack(side=tk.LEFT, padx=6)
        ttk.Button(id_frame, text="💾 保存名称", command=self._on_save_device_name).pack(side=tk.LEFT, padx=(0, 14))

        self.connected_peer_var = tk.StringVar(value="当前连接: 暂未选择设备 (请在下方点击『扫描周围设备』后点击设备一键连接)")
        ttk.Label(
            id_frame,
            textvariable=self.connected_peer_var,
            font=("Microsoft YaHei UI", 9, "bold"),
            foreground="#005fb8",
        ).pack(side=tk.LEFT, fill=tk.X, expand=True)

        # 1. Top Power Status Banner
        top_frame = ttk.LabelFrame(self.root, text=" 功耗与 Wi-Fi P2P 链路状态 (非传输时自动断链保续航) ", padding=10)
        top_frame.pack(fill=tk.X, padx=12, pady=(8, 6))

        self.power_label_var = tk.StringVar(value="● 当前状态: DEEP_IDLE (超低功耗待机 | Wi-Fi P2P 关闭 | 0 轮询)")
        self.power_label = ttk.Label(
            top_frame,
            textvariable=self.power_label_var,
            font=("Microsoft YaHei UI", 10, "bold"),
            foreground="#1b7f37",
        )
        self.power_label.pack(anchor=tk.W)

        self.p2p_info_var = tk.StringVar(
            value="本机 P2P 热点: 未开启 (可直接扫描连接手机，或开启本机热点让手机一键免密连接)"
        )
        ttk.Label(top_frame, textvariable=self.p2p_info_var, font=("Microsoft YaHei UI", 9)).pack(
            anchor=tk.W, pady=(4, 6)
        )

        btn_row = ttk.Frame(top_frame)
        btn_row.pack(fill=tk.X)

        ttk.Button(btn_row, text="🔍 扫描周围设备 (免输SSID)", command=self._on_scan_nearby).pack(
            side=tk.LEFT, padx=(0, 8)
        )
        ttk.Button(btn_row, text="⚡ 开启本机热点 (让手机发现我)", command=self._on_start_go).pack(
            side=tk.LEFT, padx=(0, 8)
        )
        ttk.Button(btn_row, text="🔌 断开当前连接", command=self._on_disconnect_peer).pack(
            side=tk.LEFT, padx=(0, 8)
        )
        ttk.Button(btn_row, text="🔧 手动输入SSID连接", command=self._on_connect_android_go).pack(
            side=tk.LEFT
        )

        # 2. Middle Split: Peers & One-Click Connect + File & Clipboard Actions
        mid_frame = ttk.Frame(self.root)
        mid_frame.pack(fill=tk.BOTH, expand=False, padx=12, pady=6)

        # Left: Peer List & One-Click Connect
        peer_box = ttk.LabelFrame(
            mid_frame,
            text=" 周围可用设备 (★为已信任设备，发现后自动连接 | 双击设备手动连接) ",
            padding=10,
        )
        peer_box.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 6))

        self.peer_list = tk.Listbox(peer_box, height=5, font=("Microsoft YaHei UI", 9))
        self.peer_list.pack(fill=tk.BOTH, expand=True)
        self.peer_list.bind("<<ListboxSelect>>", self._on_peer_select)
        self.peer_list.bind("<Double-Button-1>", self._on_connect_selected_peer)

        target_row = ttk.Frame(peer_box)
        target_row.pack(fill=tk.X, pady=(6, 0))
        ttk.Button(
            target_row,
            text="🔗 连接/设为目标",
            command=self._on_connect_selected_peer,
        ).pack(side=tk.LEFT, padx=(0, 4))
        ttk.Button(
            target_row,
            text="🔌 单独断开",
            command=self._on_disconnect_peer,
        ).pack(side=tk.LEFT, padx=(0, 4))
        ttk.Button(
            target_row,
            text="🗑️ 取消信任",
            command=self._on_forget_peer_trust,
        ).pack(side=tk.LEFT, padx=(0, 6))
        ttk.Label(target_row, text="目标 IP:").pack(side=tk.LEFT)
        self.target_ip_var = tk.StringVar(value=ANDROID_GO_DEFAULT_IP)
        ttk.Entry(target_row, textvariable=self.target_ip_var, width=13).pack(side=tk.LEFT, padx=4)

        # Right: File Transfer & Clipboard Sync Controls
        action_box = ttk.LabelFrame(mid_frame, text=" 文件高速传输 & 剪贴板同步 ", padding=10)
        action_box.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(6, 0))

        self.auto_reconnect_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(
            action_box,
            text="发现曾连接过的设备时自动重连 (★信任设备免点击自动重连)",
            variable=self.auto_reconnect_var,
            command=self._on_toggle_auto_reconnect,
        ).pack(anchor=tk.W, pady=(0, 4))

        self.auto_clip_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(
            action_box,
            text="开启剪贴板双向自动同步 (≤16KB 走低功耗微通道，不唤醒 P2P)",
            variable=self.auto_clip_var,
            command=self._on_toggle_auto_clip,
        ).pack(anchor=tk.W, pady=(0, 8))

        act_btns = ttk.Frame(action_box)
        act_btns.pack(fill=tk.X, pady=4)
        ttk.Button(act_btns, text="📁 选择文件高速发送 (支持多选/断点续传)", command=self._on_select_and_send_files).pack(
            side=tk.LEFT, padx=(0, 8)
        )
        ttk.Button(act_btns, text="📋 立即推送当前剪贴板", command=self._on_push_clipboard_now).pack(side=tk.LEFT)

        self.progress_var = tk.DoubleVar(value=0.0)
        self.progress_bar = ttk.Progressbar(action_box, variable=self.progress_var, maximum=100.0)
        self.progress_bar.pack(fill=tk.X, pady=(10, 4))

        self.progress_text_var = tk.StringVar(value=f"🔒 AES-256 端到端加密已启用 | 接收目录: {self.node.download_dir}")
        ttk.Label(action_box, textvariable=self.progress_text_var, font=("Microsoft YaHei UI", 8)).pack(anchor=tk.W)

        # 3. Bottom Log Console
        log_box = ttk.LabelFrame(self.root, text=" 运行与功耗事件日志 ", padding=8)
        log_box.pack(fill=tk.BOTH, expand=True, padx=12, pady=(4, 12))

        self.log_text = tk.Text(log_box, height=12, font=("Consolas", 9), state=tk.DISABLED, bg="#1e1e1e", fg="#d4d4d4")
        scroll = ttk.Scrollbar(log_box, command=self.log_text.yview)
        self.log_text.configure(yscrollcommand=scroll.set)
        self.log_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll.pack(side=tk.RIGHT, fill=tk.Y)

    def _on_save_device_name(self) -> None:
        name = self.device_name_var.get().strip()
        if not name:
            messagebox.showwarning("提示", "设备名称不能为空")
            return
        self.node.set_device_name(name)

    def _on_scan_nearby(self) -> None:
        threading.Thread(target=self.node.scan_nearby_devices, daemon=True).start()

    def _on_connect_selected_peer(self, _event: object = None) -> None:
        sel = self.peer_list.curselection()
        if not sel:
            if self._displayed_peers:
                idx = 0
            else:
                messagebox.showinfo("提示", "请先点击『🔍 扫描周围设备』并在列表中选择一台设备")
                return
        else:
            idx = sel[0]

        if not (0 <= idx < len(self._displayed_peers)):
            return

        peer = self._displayed_peers[idx]
        if peer.ip:
            self.target_ip_var.set(peer.ip)

        # If it's a scanned Android P2P hotspot (`p2p_scan`), warn the user before switching the laptop's main Wi-Fi!
        if peer.discovery_source == "p2p_scan" and peer.p2p_ssid:
            if not peer.p2p_passphrase and not peer.p2p_ssid.startswith("DIRECT-CP-"):
                self._on_connect_android_go(prefill_ssid=peer.p2p_ssid)
                return
            ok_switch = messagebox.askyesno(
                "连接模式提示 (推荐电脑开热点防断网)",
                f"直接让电脑作为客户端连接手机热点「{peer.p2p_ssid}」会临时切换电脑的主 Wi-Fi 网卡（导致电脑外网暂时断开）。\n\n"
                f"💡 强烈推荐【电脑不断网方案】：\n"
                f"点击『否』，程序将自动为您开启电脑虚拟直连热点 (192.168.137.1)，手机端会自动发现并免密连接，电脑主 Wi-Fi 保持不断网！\n\n"
                f"是否仍要切换电脑主 Wi-Fi 连接该手机热点？（选『否』将自动开启电脑不断网热点）",
                parent=self.root,
            )
            if not ok_switch:
                self._on_start_go()
                return

        threading.Thread(
            target=self.node.connect_to_peer_and_sync,
            args=(peer,),
            daemon=True,
        ).start()

    def _auto_start_go_if_not_connected(self) -> None:
        if self.node.connected_peer is None and not self.node.p2p_controller.is_active:
            self._on_start_go()

    def _on_start_go(self) -> None:
        def task() -> None:
            creds = self.node.start_p2p_group_on_demand()
            self._ui_queue.put(
                (
                    "p2p_info",
                    f"本机直连热点已开启: 设备名=\"{self.node.device_name}\" | SSID={creds.ssid} | 手机扫描即可一键免密连接",
                )
            )

        threading.Thread(target=task, daemon=True).start()

    def _on_connect_android_go(self, prefill_ssid: str = "DIRECT-") -> None:
        dlg = tk.Toplevel(self.root)
        dlg.title("手动连接 Wi-Fi Direct (P2P) 热点")
        dlg.geometry("380x210")
        dlg.transient(self.root)
        dlg.grab_set()

        ttk.Label(dlg, text="对端 P2P SSID (如 DIRECT-xx-Phone):").pack(anchor=tk.W, padx=16, pady=(12, 2))
        ssid_var = tk.StringVar(value=prefill_ssid)
        ttk.Entry(dlg, textvariable=ssid_var, width=36).pack(padx=16)

        ttk.Label(dlg, text="对端 P2P 密码 (若为 DIRECT-CP- 可留空自动推导):").pack(anchor=tk.W, padx=16, pady=(8, 2))
        pwd_var = tk.StringVar()
        ttk.Entry(dlg, textvariable=pwd_var, width=36).pack(padx=16)

        ttk.Label(dlg, text="对端 GO IP (默认 192.168.49.1):").pack(anchor=tk.W, padx=16, pady=(8, 2))
        ip_var = tk.StringVar(value=ANDROID_GO_DEFAULT_IP)
        ttk.Entry(dlg, textvariable=ip_var, width=36).pack(padx=16)

        def do_connect() -> None:
            from protocol import derive_p2p_passphrase

            s = ssid_var.get().strip()
            p = pwd_var.get().strip() or (derive_p2p_passphrase(s) if s.startswith("DIRECT-CP-") else "")
            ip = ip_var.get().strip() or ANDROID_GO_DEFAULT_IP
            dlg.destroy()
            if s and p:
                self.target_ip_var.set(ip)
                threading.Thread(
                    target=self.node.connect_to_android_p2p_group,
                    args=(s, p, ip),
                    daemon=True,
                ).start()

        ttk.Button(dlg, text="立即直连", command=do_connect).pack(pady=12)

    def _get_selected_peer_from_list(self) -> Optional[PeerInfo]:
        sel = self.peer_list.curselection()
        if sel and 0 <= sel[0] < len(self._displayed_peers):
            return self._displayed_peers[sel[0]]
        return self.node.connected_peer

    def _on_disconnect_peer(self) -> None:
        sel = self.peer_list.curselection()
        if sel and 0 <= sel[0] < len(self._displayed_peers):
            peer = self._displayed_peers[sel[0]]
            threading.Thread(target=self.node.disconnect_peer, args=(peer,), daemon=True).start()
        else:
            threading.Thread(target=self.node.disconnect_current_peer, daemon=True).start()

    def _on_forget_peer_trust(self) -> None:
        peer = self._get_selected_peer_from_list()
        if peer is None:
            messagebox.showinfo("提示", "请先在设备列表中选择要取消信任的设备")
            return
        threading.Thread(target=self.node.disconnect_peer, args=(peer, True), daemon=True).start()
        self.node._emit_log(f"[已取消信任] 已将 \"{peer.device_name}\" 移出信任列表，下次连接需手动同意")

    def _on_teardown_p2p(self) -> None:
        self._on_disconnect_peer()

    def _on_toggle_auto_reconnect(self) -> None:
        self.node.auto_reconnect_trusted = bool(self.auto_reconnect_var.get())

    def _on_toggle_auto_clip(self) -> None:
        self.node.auto_clipboard_sync = bool(self.auto_clip_var.get())

    def _on_push_clipboard_now(self) -> None:
        from clipboard_win32 import get_clipboard_text

        txt = get_clipboard_text()
        if not txt:
            messagebox.showinfo("提示", "当前系统剪贴板为空或非文本内容")
            return
        peer = self._get_selected_peer_from_list()
        target = (peer.ip if peer and peer.ip else None) or self.target_ip_var.get().strip() or None
        threading.Thread(target=self.node.sync_clipboard_text, args=(txt, target, peer), daemon=True).start()

    def _on_select_and_send_files(self) -> None:
        peer = self._get_selected_peer_from_list()
        files = filedialog.askopenfilenames(
            title=f"选择要发送给 [{peer.device_name if peer else self.target_ip_var.get()}] 的文件"
        )
        if not files:
            return
        target = (peer.ip if peer and peer.ip else None) or self.target_ip_var.get().strip() or ANDROID_GO_DEFAULT_IP
        target_port = peer.data_port if peer else DATA_TCP_PORT
        threading.Thread(
            target=self.node.send_files_on_demand,
            args=(list(files), target, target_port, peer),
            daemon=True,
        ).start()

    def _on_peer_select(self, _event: object) -> None:
        sel = self.peer_list.curselection()
        if not sel:
            return
        idx = sel[0]
        if 0 <= idx < len(self._displayed_peers):
            p = self._displayed_peers[idx]
            if p.ip:
                self.target_ip_var.set(p.ip)
            if p.is_connected:
                self.node.select_target_peer(p)

    def _DrainUiQueue(self) -> None:
        try:
            while True:
                kind, payload = self._ui_queue.get_nowait()
                if kind == "log":
                    self._append_log(str(payload))
                elif kind == "power":
                    st = payload
                    if st == PowerState.DEEP_IDLE:
                        self.power_label_var.set(
                            "● 当前状态: DEEP_IDLE (超低功耗待机 | Wi-Fi P2P 已关闭 | 0 轮询)"
                        )
                        self.power_label.configure(foreground="#1b7f37")
                        self.p2p_info_var.set("本机 P2P 热点: 未开启 (非文件传输时自动关闭保续航)")
                    elif st == PowerState.P2P_HANDSHAKE:
                        self.power_label_var.set("◐ 当前状态: P2P_HANDSHAKE (正在按需建立 Wi-Fi P2P 通道...)")
                        self.power_label.configure(foreground="#b58900")
                    elif st == PowerState.P2P_BURST_ACTIVE:
                        self.power_label_var.set(
                            "⚡ 当前状态: P2P_BURST_ACTIVE (Wi-Fi P2P 高速突发传输中 | 传输完 15s 自动断链)"
                        )
                        self.power_label.configure(foreground="#d9534f")
                elif kind == "p2p_info":
                    self.p2p_info_var.set(str(payload))
                elif kind == "connected_peer":
                    cp: Optional[PeerInfo] = payload  # type: ignore
                    if cp is not None:
                        self.connected_peer_var.set(
                            f"🎯 当前指定目标: {cp.device_name} [{cp.platform}] ({cp.ip}) — 定向发文件 & 剪贴板同步中"
                        )
                        if cp.ip:
                            self.target_ip_var.set(cp.ip)
                    else:
                        self.connected_peer_var.set(
                            "🔌 当前无连接设备 (在下方设备列表选中设备后点击『🔗 连接/设为目标』)"
                        )
                elif kind == "peers":
                    peers: List[PeerInfo] = payload  # type: ignore
                    self._displayed_peers = list(peers)
                    self.peer_list.delete(0, tk.END)
                    cur_id = self.node.connected_peer.device_id if self.node.connected_peer else None
                    for p in peers:
                        icon = "📱" if p.platform == "android" else "💻"
                        trusted = self.node.is_trusted_peer(p)
                        star = "★已信任 " if trusted else "[陌生设备] "
                        if p.is_connected and p.device_id == cur_id:
                            state_tag = "[🎯当前目标·已连接]"
                        elif p.is_connected:
                            state_tag = "[✅已连接·单击设为目标]"
                        elif trusted:
                            state_tag = "[⚪已断开·双击免确认连接]"
                        else:
                            state_tag = "[⚪未连接·双击发起连接请求]"
                        addr_str = p.p2p_ssid if p.discovery_source == "p2p_scan" else f"{p.ip}:{p.data_port}"
                        self.peer_list.insert(tk.END, f"{icon} {star}{p.device_name} ({addr_str})  {state_tag}")
                elif kind == "pair_request":
                    req_peer: PeerInfo = payload  # type: ignore
                    self._prompt_pair_request_dialog(req_peer)
                elif kind == "progress":
                    prog: TransferProgress = payload  # type: ignore
                    pct = (prog.transferred_bytes * 100.0 / prog.total_bytes) if prog.total_bytes else 100.0
                    self.progress_var.set(pct)
                    arrow = "↑发送" if prog.direction == "send" else "↓接收"
                    self.progress_text_var.set(
                        f"{arrow} {prog.file_name}: {pct:.1f}% ({prog.transferred_bytes // 1024} / {prog.total_bytes // 1024} KB) | {prog.speed_mbps:.2f} MB/s"
                    )
        except queue.Empty:
            pass
        self.root.after(80, self._DrainUiQueue)

    def _prompt_pair_request_dialog(self, peer: PeerInfo) -> None:
        try:
            self.root.deiconify()
            self.root.lift()
        except Exception:
            pass
        accepted = messagebox.askyesno(
            "收到陌生设备连接请求",
            f"陌生设备「{peer.device_name}」({peer.ip}) 请求与本机建立连接并同步剪贴板/传输文件。\n\n"
            f"是否同意连接并将其加入 ★已信任设备？",
            parent=self.root,
        )
        threading.Thread(
            target=self.node.respond_to_pair_request,
            args=(peer, bool(accepted)),
            daemon=True,
        ).start()

    def _append_log(self, line: str) -> None:
        ts = time.strftime("%H:%M:%S")
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.insert(tk.END, f"[{ts}] {line}\n")
        self.log_text.see(tk.END)
        self.log_text.configure(state=tk.DISABLED)

    def _on_close(self) -> None:
        self.node.stop()
        self.root.destroy()


def run_cli(args: argparse.Namespace) -> int:
    node = ConnectP2pWindowsNode(
        download_dir=Path(args.download_dir) if args.download_dir else None,
        idle_timeout_sec=args.idle_timeout,
        on_log=lambda msg: print(msg, flush=True),
    )
    node.start()
    try:
        if args.start_go:
            node.start_p2p_group_on_demand()
        if args.connect_ssid and args.connect_pass:
            node.connect_to_android_p2p_group(
                ssid=args.connect_ssid,
                passphrase=args.connect_pass,
                go_ip=args.target,
            )
        if args.send_clipboard:
            node.sync_clipboard_text(args.send_clipboard, target_ip=args.target)
        if args.send_files:
            ok = node.send_files_on_demand(args.send_files, target_ip=args.target, target_port=args.port)
            if not args.daemon:
                return 0 if ok else 1
        if args.daemon or args.cli:
            print("ConnectP2P CLI 正在运行 (按 Ctrl+C 退出)...", flush=True)
            while True:
                time.sleep(1.0)
        return 0
    except KeyboardInterrupt:
        return 0
    finally:
        node.stop()


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )
    parser = argparse.ArgumentParser(description="ConnectP2P Windows 端 (低功耗 Wi-Fi P2P 文件传输与剪贴板同步)")
    parser.add_argument("--cli", action="store_true", help="以无界面命令行守护模式运行")
    parser.add_argument("--daemon", action="store_true", help="发送文件后继续保持低功耗后台待机")
    parser.add_argument("--target", default=ANDROID_GO_DEFAULT_IP, help="对端 IP 地址 (默认 192.168.49.1)")
    parser.add_argument("--port", type=int, default=DATA_TCP_PORT, help="TCP 数据端口 (默认 45821)")
    parser.add_argument("--start-go", action="store_true", help="启动时按需拉起本机 Wi-Fi Direct GO 热点")
    parser.add_argument("--connect-ssid", help="直连安卓端 Wi-Fi P2P SSID (DIRECT-...)")
    parser.add_argument("--connect-pass", help="直连安卓端 Wi-Fi P2P 密码")
    parser.add_argument("--send-files", nargs="+", help="要发送的一个或多个文件路径")
    parser.add_argument("--send-clipboard", help="直接发送一段剪贴板文本")
    parser.add_argument("--download-dir", help="接收文件保存目录")
    parser.add_argument(
        "--idle-timeout",
        type=float,
        default=15.0,
        help="无文件传输后自动关闭 Wi-Fi P2P 的空闲秒数 (默认 15s)",
    )

    args = parser.parse_args()
    if args.cli or args.send_files or args.send_clipboard or args.start_go or args.connect_ssid:
        return run_cli(args)

    root = tk.Tk()
    ConnectP2pDesktopApp(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
