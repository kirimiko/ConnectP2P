# ConnectP2P: 低功耗跨平台 Wi-Fi P2P 文件传输与剪贴板同步协议规范 (v1.0)

## 1. 核心设计目标：为什么传统 Wi-Fi P2P 费电，以及如何做到“闲时零感知低功耗”

在 Android 与 Android、Android 与 Windows 之间使用 Wi-Fi Direct (Wi-Fi P2P) 时，存在两大核心痛点：
1. **功耗痛点**：
   - 若手机长期维持 Wi-Fi P2P Group Owner (`createGroup`) 状态，Wi-Fi 芯片必须每 100ms 发送 Beacon 帧，且无法进入 IEEE 802.11 DTIM 深度省电睡眠，持续消耗 **80~150 mA** 电流。
   - 若后台持续调用 `WifiP2pManager.discoverPeers()` 进行全信道扫描，持续消耗 **120~200 mA** 电流。
   - 长期持有 `PARTIAL_WAKE_LOCK` 和 `WifiLock` 会阻止手机 CPU 进入 Doze 深度休眠。
2. **跨平台兼容性痛点 (Android ↔ Windows)**：
   - 标准 Wi-Fi Direct 的 P2P WPS 协商在 Android 与 Windows 网卡驱动之间极易因 Group Owner Intent 冲突或 WPS PIN 弹窗失败。
   - 通过 **Autonomous Group Owner (自治 GO 模式 / Legacy 兼容凭据直连)**：
     - **Android 作为 GO**：调用 `WifiP2pManager.createGroup()` 生成 `DIRECT-xx-xxx` 热点与密码，固定 IP `192.168.49.1`；另一台 Android 使用 Android 10+ 的 `WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pwd)` 免确认直连，Windows 使用临时 WLAN Profile 或 WinRT `WiFiDirectDevice` 秒连。
     - **Windows 作为 GO**：调用 WinRT `WiFiDirectAdvertisementPublisher`（开启 `IsAutonomousGroupOwnerEnabled = true` 与 `LegacySettings`）生成 `DIRECT-xx-xxx`，固定 IP `192.168.137.1`；Android 使用 `WifiP2pConfig.Builder` 免弹窗秒连。

---

## 2. 三态功耗状态机 (Power State Machine)

系统严格运行在以下三个功耗状态之间，**非文件传输时绝不维持 Wi-Fi P2P 组网，绝不轮询，绝不持有 WakeLock/WifiLock**：

```
                   [剪贴板文本变更 (<16KB)]
                  ┌──────────────────────┐
                  ▼                      │ (毫秒级发完即休眠)
┌────────────────────────────────────────┴───┐
│  STATE 0: DEEP_IDLE (深度休眠 / 待机态)     │
│  - Wi-Fi P2P: OFF (无 Group, 无 Peer 扫描)  │
│  - WakeLock / WifiLock: RELEASED           │
│  - 控制通道: BLE 硬件过滤低功耗广播 (<0.8mA) │
│             或同局域网被动事件套接字 (0轮询) │
│  - 灭屏策略: 灭屏时暂停一切主动扫描          │
└─────────────────┬──────────────────────────┘
                  │
                  │ 触发条件: 用户发起文件传输 / 大图剪贴板 (>16KB)
                  │ 发送控制信令: P2P_WAKE_REQUEST
                  ▼
┌────────────────────────────────────────────┐
│  STATE 1: P2P_HANDSHAKE (按需突发建链态)    │
│  - GO端按需拉起 Wi-Fi Direct Group          │
│  - 通过控制信令/扫码下发 (SSID, 密码, IP, 端口)│
│  - Client端通过凭据免弹窗直连 (< 1.5s)       │
└─────────────────┬──────────────────────────┘
                  │ TCP 数据通道建立
                  ▼
┌────────────────────────────────────────────┐
│  STATE 2: P2P_BURST_ACTIVE (高速传输态)     │
│  - 仅在传输期间持有 WifiLock(LOW_LATENCY)   │
│    与带超时保护的 PARTIAL_WAKE_LOCK         │
│  - 256KB 缓冲流式传输 + 断点续传 + SHA-256  │
│  - 传输全部完成 → 启动空闲倒计时 (默认 15s) │
└─────────────────┬──────────────────────────┘
                  │ 传输空闲满 15s (IDLE_TIMEOUT) 或手动断开
                  │ 自动调用 removeGroup() + 释放所有锁
                  ▼
         回到 STATE 0: DEEP_IDLE
```

---

## 3. 双通道架构规范

### 3.1 通道一：超低功耗控制与微数据通道 (Control & Micro-Data Channel)
负责：**设备发现、剪贴板文本同步（≤ 16KB）、以及唤醒 Wi-Fi P2P 的信令交换**。
支持两种物理承载（自动优选）：
1. **BLE GATT 低功耗通道**（跨网段/户外无路由器场景）：
   - Service UUID: `0000c0de-0000-1000-8000-00805f9b34fb`
   - Characteristic UUID (Control & Wakeup): `0000c0df-0000-1000-8000-00805f9b34fb` (Write / Notify)
   - Characteristic UUID (Clipboard Micro-Data): `0000c0e0-0000-1000-8000-00805f9b34fb` (WriteWithoutResponse / Notify, 支持 MTU 512 分片)
   - Android 扫描策略：强制使用 `ScanSettings.SCAN_MODE_LOW_POWER` + 硬件 `ScanFilter.Builder().setServiceUuid(...)`，将过滤卸载至蓝牙控制器芯片，不唤醒 AP CPU。
2. **轻量级事件控制信令 (UDP Multicast / Unicast 端口 `45820`)**：
   - 当设备处于同一局域网或已连接 P2P 时，通过事件驱动发送控制包（无周期性高频心跳，仅在状态变化、剪贴板变更或发起文件传输时单次发送）。

### 3.2 通道二：按需高速 Wi-Fi P2P 数据通道 (Burst Data Channel)
负责：**大文件/多文件高速传输、大尺寸剪贴板（图片/长文本 > 16KB）传输**。
- **监听端口**：TCP `45821`
- **默认 GO 地址**：
  - Android Group Owner: `192.168.49.1:45821`
  - Windows Autonomous GO: `192.168.137.1:45821`

---

## 4. 报文帧格式 (Wire Protocol)

所有 TCP 通道帧采用 **定长头部 (9 Bytes) + JSON 元数据 / 二进制载荷**：

```
+----------------+------------------+------------------------+-------------------+
| Magic (2B)     | Frame Type (1B)  | Header Length (4B, BE) | Header JSON (UTF8)|
| 0x50 0x32 ('P2')| uint8            | uint32                 | N bytes           |
+----------------+------------------+------------------------+-------------------+
| Binary Payload (Optional, length specified in Header JSON "payload_size")      |
+--------------------------------------------------------------------------------+
```

### 4.1 帧类型定义 (`FrameType`)

| 值 (`uint8`) | 名称 | 用途说明 |
| :--- | :--- | :--- |
| `0x01` | `HELLO` | 握手认证，携带 `device_id`, `device_name`, `platform` (`android`/`windows`), `auth_token` |
| `0x02` | `CLIPBOARD_SYNC` | 剪贴板同步，携带 `clip_id` (SHA-256前16位), `mime` (`text/plain`/`image/png`), `timestamp`, `origin_device_id`, 以及二进制载荷 |
| `0x03` | `FILE_OFFER` | 请求发送文件/支持断点续传查询，携带 `file_id`, `file_name`, `file_size`, `sha256` |
| `0x04` | `FILE_ACCEPT` | 接收端响应，携带 `file_id`, `accepted` (bool), `resume_offset` (uint64，用于断点续传) |
| `0x05` | `FILE_DATA` | 紧随其后的是从 `resume_offset` 开始的原始文件二进制流 |
| `0x06` | `FILE_ACK` | 文件接收完成校验结果，携带 `file_id`, `success` (bool), `verified_sha256` |
| `0x07` | `P2P_WAKE_REQUEST` | 请求对端拉起 Wi-Fi P2P Group Owner（通过 BLE 或控制通道发送） |
| `0x08` | `P2P_GROUP_READY` | GO 已就绪，返回 `ssid` (`DIRECT-...`), `passphrase`, `go_ip`, `port`, `band_mhz` |
| `0x09` | `P2P_TEARDOWN` | 通知对端传输完毕，立即拆除 Wi-Fi P2P Group 进入低功耗待机 |
| `0x0A` | `PING_PONG` | 按需可达性探测 |

---

## 5. 剪贴板同步防回环与 Android 10+ 后台限制解决方案

1. **防死循环回环 (Loop Suppression)**：
   - 每次写入系统剪贴板前，计算内容摘要 `clip_hash = SHA256(content)[:16]` 并记录在 `last_applied_hash`。
   - 当系统触发剪贴板变更回调时，若当前内容摘要等于 `last_applied_hash`，立即忽略，防止 A→B→A 无限乒乓同步导致耗电。
2. **Android 10+ (API 29+) 剪贴板权限与省电兼顾**：
   - Android 10 及以上禁止后台应用静默读取剪贴板（除非具有输入法/焦点）。
   - 本工具采用 **零耗电事件触发方案**：
     - **远端 → 手机**：后台服务可直接写入手机系统剪贴板（Android 允许后台 `setPrimaryClip`），毫秒级完成。
     - **手机 → 远端**：
       1. 应用在前台或获得焦点（`onWindowFocusChanged`）时自动监听并同步；
       2. 提供 **下拉通知栏快捷磁贴 (`Quick Settings TileService`)** 与 **系统“分享到 ConnectP2P” (`ACTION_SEND`)**，任意界面复制后点一下磁贴或分享即可瞬间同步到电脑/另一台手机，无需常驻无障碍服务耗电！
