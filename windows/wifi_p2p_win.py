"""
Windows Wi-Fi P2P (Wi-Fi Direct) Controller with On-Demand Burst Lifecycle.

Supports two interoperability modes with Android phones:
1. Windows as Wi-Fi Direct Autonomous Group Owner (GO):
   - Uses WinRT `Windows.Devices.WiFiDirect.WiFiDirectAdvertisementPublisher`
     with `IsAutonomousGroupOwnerEnabled = True` and `LegacySettings` enabled.
   - Broadcasts `DIRECT-PC-<Name>` with a WPA2 passphrase and assigns `192.168.137.1`.
   - Android connects directly via `WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pwd)`
     with zero WPS PIN dialog!
2. Windows as Client connecting to Android's Wi-Fi Direct Group Owner (`DIRECT-xx-...`):
   - Connects using an ephemeral WLAN profile (`netsh wlan`) to Android's P2P group (`192.168.49.1`),
     and removes the ephemeral profile immediately upon idle teardown.

Low-Power Guarantee:
- Wi-Fi Direct group or client connection is ONLY active during `P2P_BURST_ACTIVE`.
- Calling `teardown()` immediately stops the WinRT publisher or disconnects the ephemeral P2P profile.
"""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wintypes
import logging
import secrets
import socket
import string
import subprocess
import sys
import tempfile
import threading
import time
import xml.sax.saxutils as saxutils
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from protocol import (
    ANDROID_GO_DEFAULT_IP,
    DATA_TCP_PORT,
    WINDOWS_GO_DEFAULT_IP,
    PeerInfo,
    decode_p2p_ssid,
    derive_p2p_passphrase,
    encode_p2p_ssid,
)

logger = logging.getLogger("ConnectP2P.WiFiDirect")

_WINRT_PREAMBLE = r"""
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Runtime.WindowsRuntime -ErrorAction SilentlyContinue
[void][Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType=WindowsRuntime]
[void][Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType=WindowsRuntime]
[void][Windows.Networking.NetworkOperators.NetworkOperatorTetheringAccessPointConfiguration, Windows.Networking.NetworkOperators, ContentType=WindowsRuntime]
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
$asTaskAction = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' })[0]
Function Await($WinRtTask, $ResultType) {
    $netTask = $asTaskGeneric.MakeGenericMethod($ResultType).Invoke($null, @($WinRtTask))
    $netTask.Wait(8000) | Out-Null
    return $netTask.Result
}
Function AwaitAction($WinRtAction) {
    $netTask = $asTaskAction.Invoke($null, @($WinRtAction))
    $netTask.Wait(8000) | Out-Null
}
Function Get-TetheringManager {
    $p = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
    if ($null -eq $p) {
        $p = [Windows.Networking.Connectivity.NetworkInformation]::GetConnectionProfiles() | Where-Object { $_.IsWlanConnectionProfile } | Select-Object -First 1
    }
    if ($null -eq $p) { return $null }
    return [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($p)
}
"""


@dataclass
class P2pGroupCredentials:
    ssid: str
    passphrase: str
    go_ip: str
    port: int = DATA_TCP_PORT
    role: str = "GO"  # "GO" or "CLIENT"
    hotspot_on: bool = True


class WindowsWifiP2pController:
    """
    Manages on-demand creation, connection, and automatic idle teardown of
    Wi-Fi Direct (Wi-Fi P2P) links on Windows 10/11.
    """

    def __init__(self, device_name: Optional[str] = None) -> None:
        self.device_name = device_name or socket.gethostname()[:10]
        self._lock = threading.Lock()
        self._ps_proc: Optional[subprocess.Popen] = None
        self._active_creds: Optional[P2pGroupCredentials] = None
        self._temp_profile_name: Optional[str] = None

    @property
    def is_active(self) -> bool:
        with self._lock:
            return self._active_creds is not None

    @property
    def active_credentials(self) -> Optional[P2pGroupCredentials]:
        with self._lock:
            return self._active_creds

    def start_autonomous_group_owner(
        self,
        ssid: Optional[str] = None,
        passphrase: Optional[str] = None,
        port: int = DATA_TCP_PORT,
    ) -> P2pGroupCredentials:
        """
        Start a Windows Wi-Fi Direct Autonomous Group Owner (`DIRECT-PC-...`) on demand.
        If already active, returns existing credentials.
        """
        with self._lock:
            if self._active_creds and self._active_creds.role == "GO":
                return self._active_creds

        target_ssid = ssid or encode_p2p_ssid(self.device_name)
        target_pass = passphrase or derive_p2p_passphrase(target_ssid)

        # Windows Mobile Hotspot runs on the "Microsoft Wi-Fi Direct Virtual Adapter", so the primary
        # WLAN connection stays up. Windows turns the hotspot off by itself after 5 min without clients
        # unless the no-connections timeout is disabled.
        body = f"""
$tm = Get-TetheringManager
if ($null -eq $tm) {{ Write-Output "RESULT:NoTetheringManager::"; return }}
try {{ AwaitAction ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::DisableNoConnectionsTimeoutAsync()) }} catch {{}}
if ($tm.TetheringOperationalState -ne 'On') {{
    $cfg = New-Object Windows.Networking.NetworkOperators.NetworkOperatorTetheringAccessPointConfiguration
    $cfg.Ssid = '{target_ssid}'
    $cfg.Passphrase = '{target_pass}'
    try {{ AwaitAction ($tm.ConfigureAccessPointAsync($cfg)) }} catch {{}}
    try {{
        $r = Await ($tm.StartTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
        if ($null -ne $r -and $r.Status -ne 'Success') {{ Write-Output "ERR:$($r.Status) $($r.AdditionalErrorMessage)" }}
    }} catch {{ Write-Output "ERR:$($_.Exception.Message)" }}
}}
$cur = $tm.GetCurrentAccessPointConfiguration()
Write-Output "RESULT:$($tm.TetheringOperationalState):$($cur.Ssid):$($cur.Passphrase)"
"""
        logger.info("Starting Windows Mobile Hotspot: %s", target_ssid)
        out = self._run_winrt_script(body, timeout=20.0)
        state, actual_ssid, actual_pass = "", target_ssid, target_pass
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("ERR:"):
                logger.warning("Mobile Hotspot start error: %s", line[4:])
            elif line.startswith("RESULT:"):
                parts = line[len("RESULT:"):].split(":", 2)
                state = parts[0] if parts else ""
                if len(parts) >= 3 and parts[1]:
                    actual_ssid, actual_pass = parts[1], parts[2]

        hotspot_on = state == "On"
        go_ip = None
        if hotspot_on:
            for _ in range(12):
                go_ip = self._detect_local_p2p_ip()
                if go_ip:
                    break
                time.sleep(0.25)
        creds = P2pGroupCredentials(
            ssid=actual_ssid,
            passphrase=actual_pass,
            go_ip=go_ip or WINDOWS_GO_DEFAULT_IP,
            port=port,
            role="GO",
            hotspot_on=hotspot_on,
        )
        if hotspot_on:
            with self._lock:
                self._active_creds = creds
            logger.info("Mobile Hotspot ON at %s (SSID=%s)", creds.go_ip, actual_ssid)
        else:
            logger.warning("Mobile Hotspot did not turn on (state=%r)", state)
        return creds

    def teardown_local_state(self) -> None:
        with self._lock:
            if self._active_creds is not None and self._active_creds.role == "GO":
                self._active_creds = None

    def is_hotspot_on(self) -> bool:
        out = self._run_winrt_script(
            '$tm = Get-TetheringManager\nif ($null -ne $tm) { Write-Output "STATE:$($tm.TetheringOperationalState)" }',
            timeout=10.0,
        )
        return "STATE:On" in out

    @staticmethod
    def _run_winrt_script(body: str, timeout: float = 15.0) -> str:
        script = _WINRT_PREAMBLE + "\ntry {\n" + body + "\n} catch { Write-Output \"ERR:$($_.Exception.Message)\" }\n"
        try:
            res = subprocess.run(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
                capture_output=True,
                text=True,
                timeout=timeout,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            return res.stdout or ""
        except Exception as exc:
            logger.warning("WinRT PowerShell call failed: %s", exc)
            return ""

    def connect_to_android_group(
        self,
        ssid: str,
        passphrase: str,
        go_ip: str = ANDROID_GO_DEFAULT_IP,
        port: int = DATA_TCP_PORT,
        timeout_sec: float = 12.0,
    ) -> bool:
        """
        Connect Windows laptop as a client to an Android Wi-Fi P2P Group (`DIRECT-xx-...`).
        Uses an ephemeral WLAN profile that is deleted upon teardown.
        """
        safe_ssid = saxutils.escape(ssid)
        safe_pass = saxutils.escape(passphrase)
        ssid_hex = ssid.encode("utf-8").hex().upper()

        profile_xml = f"""<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
    <name>{safe_ssid}</name>
    <SSIDConfig>
        <SSID>
            <hex>{ssid_hex}</hex>
            <name>{safe_ssid}</name>
        </SSID>
    </SSIDConfig>
    <connectionType>ESS</connectionType>
    <connectionMode>manual</connectionMode>
    <MSM>
        <security>
            <authEncryption>
                <authentication>WPA2PSK</authentication>
                <encryption>AES</encryption>
                <useOneX>false</useOneX>
            </authEncryption>
            <sharedKey>
                <keyType>passPhrase</keyType>
                <protected>false</protected>
                <keyMaterial>{safe_pass}</keyMaterial>
            </sharedKey>
        </security>
    </MSM>
</WLANProfile>"""

        tmp_file = Path(tempfile.gettempdir()) / f"connect_p2p_{ int(time.time()) }.xml"
        try:
            tmp_file.write_text(profile_xml, encoding="utf-8")
            subprocess.run(
                ["netsh", "wlan", "add", "profile", f"filename={tmp_file}", "user=current"],
                capture_output=True,
                text=True,
                timeout=5,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            subprocess.run(
                ["netsh", "wlan", "connect", f"name={ssid}", f"ssid={ssid}"],
                capture_output=True,
                text=True,
                timeout=5,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except Exception as exc:
            logger.error("Failed to invoke netsh wlan connect: %s", exc)
            return False
        finally:
            try:
                tmp_file.unlink(missing_ok=True)
            except Exception:
                pass

        # Wait until Android GO IP (192.168.49.1) is reachable
        deadline = time.time() + timeout_sec
        while time.time() < deadline:
            if self._probe_tcp(go_ip, port, timeout=1.0):
                with self._lock:
                    self._temp_profile_name = ssid
                    self._active_creds = P2pGroupCredentials(
                        ssid=ssid,
                        passphrase=passphrase,
                        go_ip=go_ip,
                        port=port,
                        role="CLIENT",
                    )
                logger.info("Connected to Android Wi-Fi P2P GO at %s:%d", go_ip, port)
                return True
            time.sleep(0.5)

        with self._lock:
            self._temp_profile_name = ssid
            self._active_creds = P2pGroupCredentials(
                ssid=ssid,
                passphrase=passphrase,
                go_ip=go_ip,
                port=port,
                role="CLIENT",
            )
        return False

    def teardown(self) -> None:
        """
        Immediately tear down the active Wi-Fi Direct Group or temporary Client profile
        so both Windows and the peer Android phone return to DEEP_IDLE power state.
        """
        with self._lock:
            proc = self._ps_proc
            self._ps_proc = None
            profile_name = self._temp_profile_name
            self._temp_profile_name = None
            creds = self._active_creds
            self._active_creds = None

        if proc is not None:
            logger.info("Stopping Windows Wi-Fi Direct Autonomous GO...")
            try:
                if proc.stdin:
                    proc.stdin.write("STOP\n")
                    proc.stdin.flush()
                proc.wait(timeout=3.0)
            except Exception:
                try:
                    proc.terminate()
                except Exception:
                    pass

        if profile_name:
            logger.info("Removing ephemeral Wi-Fi P2P client profile: %s", profile_name)
            try:
                subprocess.run(
                    ["netsh", "wlan", "delete", "profile", f"name={profile_name}"],
                    capture_output=True,
                    text=True,
                    timeout=5,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
            except Exception:
                pass

        if creds and creds.role == "GO":
            logger.info("Stopping Windows Mobile Hotspot...")
            self._run_winrt_script(
                """
$tm = Get-TetheringManager
if ($null -ne $tm -and $tm.TetheringOperationalState -eq 'On') {
    Await ($tm.StopTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult]) | Out-Null
}
try { AwaitAction ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::EnableNoConnectionsTimeoutAsync()) } catch {}
""",
                timeout=12.0,
            )

        if creds:
            logger.info("Wi-Fi P2P link torn down (%s). Entered DEEP_IDLE state.", creds.ssid)

    def scan_nearby_p2p_networks(self, trigger_active_scan: bool = False) -> list[PeerInfo]:
        """
        Scan nearby Wi-Fi Direct (`DIRECT-CP-*`) networks visible to the Windows WLAN adapter
        using the Win32 Native Wi-Fi `WlanGetNetworkBssList` API (which includes Wi-Fi Direct
        Group Owners that `netsh wlan show networks` hides!).
        """
        discovered: list[PeerInfo] = []
        own_ssid = self._active_creds.ssid if self._active_creds else None
        seen_ssids: set[str] = set()

        if sys.platform == "win32":
            try:
                for ssid in self._scan_bss_ssids_win32(trigger_scan=trigger_active_scan):
                    if not ssid.startswith("DIRECT-CP-") or ssid == own_ssid or ssid in seen_ssids:
                        continue
                    seen_ssids.add(ssid)
                    friendly_name, is_auto = decode_p2p_ssid(ssid)
                    auto_pass = derive_p2p_passphrase(ssid) if is_auto else None
                    discovered.append(
                        PeerInfo(
                            device_id=f"p2p-{ssid}",
                            device_name=friendly_name,
                            platform="android",
                            ip=ANDROID_GO_DEFAULT_IP,
                            data_port=DATA_TCP_PORT,
                            p2p_ssid=ssid,
                            p2p_passphrase=auto_pass,
                            p2p_go_ip=ANDROID_GO_DEFAULT_IP,
                            discovery_source="p2p_scan",
                        )
                    )
            except Exception as exc:
                logger.debug("Win32 BSS scan skipped: %s", exc)

        return discovered

    @staticmethod
    def _scan_bss_ssids_win32(trigger_scan: bool = False) -> list[str]:
        """Read raw 802.11 BSS table via `wlanapi.WlanGetNetworkBssList` (zero main Wi-Fi disruption)."""
        ssids: list[str] = []
        wlanapi = ctypes.windll.wlanapi

        class GUID(ctypes.Structure):
            _fields_ = [
                ("Data1", wintypes.DWORD),
                ("Data2", wintypes.WORD),
                ("Data3", wintypes.WORD),
                ("Data4", ctypes.c_ubyte * 8),
            ]

        class WLAN_INTERFACE_INFO(ctypes.Structure):
            _fields_ = [
                ("InterfaceGuid", GUID),
                ("strInterfaceDescription", ctypes.c_wchar * 256),
                ("isState", ctypes.c_int),
            ]

        class WLAN_INTERFACE_INFO_LIST(ctypes.Structure):
            _fields_ = [
                ("dwNumberOfItems", wintypes.DWORD),
                ("dwIndex", wintypes.DWORD),
                ("InterfaceInfo", WLAN_INTERFACE_INFO * 1),
            ]

        class DOT11_SSID(ctypes.Structure):
            _fields_ = [
                ("uSSIDLength", wintypes.ULONG),
                ("ucSSID", ctypes.c_ubyte * 32),
            ]

        class WLAN_BSS_ENTRY(ctypes.Structure):
            _fields_ = [
                ("dot11Ssid", DOT11_SSID),
                ("uPhyId", wintypes.ULONG),
                ("dot11Bssid", ctypes.c_ubyte * 6),
                ("dot11BssType", ctypes.c_int),
                ("dot11BssPhyType", ctypes.c_int),
                ("lRssi", wintypes.LONG),
                ("uLinkQuality", wintypes.ULONG),
                ("bInRegDomain", wintypes.BOOLEAN),
                ("usBeaconPeriod", wintypes.USHORT),
                ("ullTimestamp", ctypes.c_ulonglong),
                ("ullHostTimestamp", wintypes.ullonglong if hasattr(wintypes, "ullonglong") else ctypes.c_ulonglong),
                ("usCapabilityInformation", wintypes.USHORT),
                ("ulChCenterFrequency", wintypes.ULONG),
                ("wlanRateSet", ctypes.c_byte * 256),
                ("ulIeOffset", wintypes.ULONG),
                ("ulIeSize", wintypes.ULONG),
            ]

        neg_ver = wintypes.DWORD()
        client_handle = wintypes.HANDLE()
        if wlanapi.WlanOpenHandle(2, None, ctypes.byref(neg_ver), ctypes.byref(client_handle)) != 0:
            return ssids

        p_if_list = ctypes.POINTER(WLAN_INTERFACE_INFO_LIST)()
        try:
            if wlanapi.WlanEnumInterfaces(client_handle, None, ctypes.byref(p_if_list)) != 0 or not p_if_list:
                return ssids
            count = p_if_list.contents.dwNumberOfItems
            if count <= 0:
                return ssids
            guid = p_if_list.contents.InterfaceInfo[0].InterfaceGuid
            if trigger_scan:
                wlanapi.WlanScan(client_handle, ctypes.byref(guid), None, None, None)
                time.sleep(0.35)

            p_bss = ctypes.c_void_p()
            res = wlanapi.WlanGetNetworkBssList(
                client_handle,
                ctypes.byref(guid),
                None,
                0,
                False,
                None,
                ctypes.byref(p_bss),
            )
            if res == 0 and p_bss.value:
                try:
                    hdr = (wintypes.DWORD * 2).from_address(p_bss.value)
                    num_items = hdr[1]
                    arr = (WLAN_BSS_ENTRY * num_items).from_address(p_bss.value + 8)
                    for i in range(num_items):
                        entry = arr[i]
                        length = min(32, int(entry.dot11Ssid.uSSIDLength))
                        if length > 0:
                            s = bytes(entry.dot11Ssid.ucSSID[:length]).decode("utf-8", errors="replace").strip()
                            if s:
                                ssids.append(s)
                finally:
                    wlanapi.WlanFreeMemory(p_bss)
        finally:
            if p_if_list:
                wlanapi.WlanFreeMemory(p_if_list)
            wlanapi.WlanCloseHandle(client_handle, None)
        return ssids

    @staticmethod
    def _probe_tcp(host: str, port: int, timeout: float = 1.0) -> bool:
        try:
            with socket.create_connection((host, port), timeout=timeout):
                return True
        except OSError:
            return False

    @staticmethod
    def _detect_local_p2p_ip() -> Optional[str]:
        """Find local Wi-Fi Direct virtual adapter IP (192.168.137.x or 192.168.49.x)."""
        try:
            _, _, ips = socket.gethostbyname_ex(socket.gethostname())
            for ip in ips:
                if ip.startswith("192.168.137.") or ip.startswith("192.168.49."):
                    return ip
        except OSError:
            pass
        return None

    @staticmethod
    def _detect_primary_lan_ip() -> str:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("8.8.8.8", 80))
                return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"
