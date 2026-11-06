# ConnectP2P — 低功耗 Wi-Fi P2P 文件传输与剪贴板同步工具

支持 **Android ↔ Android** 以及 **Android ↔ Windows 10/11 笔记本** 之间的无路由器直连文件高速传输与双向剪贴板同步，专为**非传输时零感知功耗、不影响手机续航**设计，并内置 **AES-256 端到端应用层加密**。

---

## 一、 核心特性与架构设计

### 1. 超低功耗三态状态机（非传输时绝不耗电）
传统 Wi-Fi Direct 工具在后台长期维持 `createGroup()`（Group Owner 每 100ms 发射一次 Beacon 帧，持续消耗 80~150mA）或周期性全信道扫描（120~200mA），并常驻持有 `PARTIAL_WAKE_LOCK`。

**ConnectP2P 采用“超低功耗控制微通道 + 按需突发 Wi-Fi P2P 高速通道”双层架构**：
1. **`DEEP_IDLE`（深度待机态，99% 的日常时间）**：
   - **Wi-Fi P2P 完全关闭**：不维持 P2P Group、不执行后台循环扫描。
   - **零锁占用**：完全释放 `WakeLock` 与 `WifiLock`，允许手机 CPU 进入深度 Doze、Wi-Fi 进入 DTIM 睡眠。
   - **硬件过滤低功耗控制通道 (< 0.8 mA)**：
     - Android 端使用 BLE `SCAN_MODE_LOW_POWER` + 蓝牙控制器硬件过滤 (`ScanFilter` by Service UUID `0000c0de-...`)，灭屏自动暂停扫描。
     - Windows 端使用 Win32 内核事件 `AddClipboardFormatListener` (`WM_CLIPBOARDUPDATE`) 监听剪贴板，0% CPU 轮询。
   - **微剪贴板免唤醒直达**：复制 ≤ 16KB 的文本时，直接通过微通道瞬时单播同步，**完全不唤醒 Wi-Fi P2P 热点**。
2. **`P2P_HANDSHAKE`（按需突发建链态）**：
   - 支持自定义中英文设备名称（自动编码进 `DIRECT-CP-...` SSID 并派生确定性密钥），扫描周围设备后**一键免输 SSID/密码直连**。
3. **`P2P_BURST_ACTIVE`（高速突发传输态）**：
   - 256 KB 缓冲流式传输 + `.part` 断点续传 + SHA-256 全量完整性校验。
   - **空闲 15 秒自动断链 (`IdlePowerWatchdog` / `PowerPolicyManager`)**：传输队列归零满 15 秒后，自动调用 `removeGroup()` 拆除 Wi-Fi P2P 链路并释放所有锁，回归 `DEEP_IDLE`。

### 2. 安全与设备连接管理
- **应用层端到端加密（AES-256-GCM + AES-256-CTR）**：
  - 所有控制帧头与剪贴板载荷均采用 **AES-256-GCM**（96-bit 随机 Nonce + 128-bit 认证标签）加密防篡改；
  - 大文件数据流采用 **AES-256-CTR**（每次传输独立 128-bit 随机 IV）流式加密 + **SHA-256** 全文校验。
- **陌生设备手动授权 & 信任设备自动重连**：
  - 首次连接**陌生设备**时，被连接方会收到弹窗/通知栏确认请求（`✅ 同意并信任` / `❌ 拒绝`），未授权前严格拦截一切剪贴板与文件数据；
  - 同意后自动记为 **`★已信任` 设备**，后续断开再发现时支持自动重连，也支持随时针对单台设备**单独断开**或**取消信任**。
- **定向单播传输**：
  - 支持在多设备列表中指定目标设备定向发送文件与剪贴板，绝不向无关设备广播。

---

## 二、 目录结构

```text
ConnectP2P/
├── protocol/
│   ├── PROTOCOL.md             # 双通道架构、三态功耗机、加密帧格式与跨平台直连规范
│   └── schema.json             # 端口、UUID、帧类型与功耗超时常量定义
├── windows/                    # Windows 10/11 客户端
│   ├── main.py                 # 桌面 GUI 界面 + 命令行 CLI 入口
│   ├── coordinator.py          # 节点协调器 (设备信任授权、定向传输、功耗状态机)
│   ├── wifi_p2p_win.py         # WinRT WiFiDirectAdvertisementPublisher (Autonomous GO) & 热点扫描直连
│   ├── clipboard_win32.py      # Win32 WM_CLIPBOARDUPDATE 零轮询监听 + SHA-256 防回环写入
│   ├── control_channel.py      # 低功耗事件控制与微剪贴板通道 (UDP + BLE 分片重组)
│   ├── transport.py            # 高速加密 TCP 文件传输引擎 (AES-256-CTR、断点续传、SHA-256)
│   └── protocol.py             # 协议编解码、AES-256-GCM/CTR 加解密、SSID 昵称编解码
├── android/                    # Android 客户端工程 (Kotlin, MinSDK 26 / TargetSDK 36)
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/connectp2p/app/
│       │   ├── protocol/P2pProtocol.kt             # 帧编解码、AES-256-GCM/CTR、BLE 分片
│       │   ├── power/PowerPolicyManager.kt         # 锁生命周期管理、灭屏感知、15s 空闲断链
│       │   ├── ble/BleLowPowerChannel.kt           # 硬件过滤 BLE 低功耗广播/扫描/GATT 微通道
│       │   ├── p2p/WifiP2pBurstManager.kt          # 5GHz Autonomous GO 与免输密码直连
│       │   ├── p2p/P2pTransportEngine.kt           # 断点续传加密 TCP 引擎与事件套接字
│       │   ├── clipboard/ClipboardSyncHelper.kt    # 防回环剪贴板同步器
│       │   ├── clipboard/ClipboardSyncActivity.kt  # 后台状态栏/磁贴瞬时焦点读取窗口
│       │   ├── clipboard/ClipboardTileService.kt   # 下拉通知栏快捷磁贴
│       │   ├── service/P2pTransferService.kt       # 协调服务、设备信任管理与通知栏交互
│       │   └── ui/MainActivity.kt                  # 主界面 + 系统“分享到 ConnectP2P”接收器
│       └── res/layout/activity_main.xml
└── tests/
    └── test_end_to_end.py      # 端到端自动化测试套件
```

---

## 三、 快速开始

### 1. 运行 Windows 端 (GUI 或 CLI)
安装依赖（`pycryptodome` 用于 AES-256-GCM/CTR 硬件加速加密）：
```powershell
pip install pycryptodome
```
启动桌面端或命令行：
```powershell
# 启动桌面图形界面 (GUI)
python windows/main.py

# 或使用命令行定向发送文件
python windows/main.py --send-files D:\data\archive.zip --target 192.168.49.1
```

### 2. 安装与使用 Android 端
1. 直接安装根目录下的 `ConnectP2P-v1.0.0-release.apk`，或在 `android/` 目录执行 `./gradlew assembleRelease` 编译安装。
2. **连接设备**：
   - 在顶部设置本机名称并点击 **“💾 保存”**。
   - **同局域网/热点下**：点击 **“🔍 扫描周围设备”**，在列表中点击目标设备发起连接（首次连接陌生设备需对方点击“同意并信任”）。
   - **无路由器户外纯直连**：在任意一端点击 **“⚡ 开启本机热点”**，另一端点击 **“🔍 扫描周围设备”** 即可看到对方设备名称并一键免密连接。
3. **文件接收保存路径**：
   - **Windows 端**：默认保存在 `C:\Users\<用户名>\Downloads\ConnectP2P\`
   - **Android 端**：默认保存在公共下载目录 `内部存储/Download/ConnectP2P/`（系统「文件管理 -> 下载」可直接查看）

### 3. 运行自动化测试
```powershell
python tests/test_end_to_end.py
```
