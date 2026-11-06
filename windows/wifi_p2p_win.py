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

import logging
import secrets
import socket
import string
import subprocess
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


@dataclass
class P2pGroupCredentials:
    ssid: str
    passphrase: str
    go_ip: str
    port: int = DATA_TCP_PORT
    role: str = "GO"  # "GO" or "CLIENT"


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

        ps_script = f"""
$ErrorActionPreference = 'Stop'
try {{
    [void][Windows.Devices.WiFiDirect.WiFiDirectAdvertisementPublisher, Windows.Devices.WiFiDirect, ContentType=WindowsRuntime]
    [void][Windows.Security.Credentials.PasswordCredential, Windows.Security.Credentials, ContentType=WindowsRuntime]

    $publisher = New-Object Windows.Devices.WiFiDirect.WiFiDirectAdvertisementPublisher
    $publisher.Advertisement.IsAutonomousGroupOwnerEnabled = $true
    $publisher.Advertisement.ListenStateDiscoverability = [Windows.Devices.WiFiDirect.WiFiDirectAdvertisementListenStateDiscoverability]::Normal
    $publisher.Advertisement.LegacySettings.IsEnabled = $true
    $publisher.Advertisement.LegacySettings.Ssid = '{target_ssid}'
    $cred = New-Object Windows.Security.Credentials.PasswordCredential
    $cred.Password = '{target_pass}'
    $publisher.Advertisement.LegacySettings.Passphrase = $cred

    $publisher.Start()
    Write-Output "READY:{target_ssid}:{target_pass}"
    [Console]::Out.Flush()

    while ($true) {{
        $line = [Console]::In.ReadLine()
        if ($null -eq $line -or $line -eq 'STOP') {{
            break
        }}
    }}
    $publisher.Stop()
}} catch {{
    Write-Output "ERROR:$($_.Exception.Message)"
    [Console]::Out.Flush()
}}
"""
        logger.info("Starting on-demand Windows Wi-Fi Direct Autonomous GO: %s", target_ssid)
        try:
            proc = subprocess.Popen(
                [
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    ps_script,
                ],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                creationflags= getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            line = ""
            if proc.stdout:
                line = proc.stdout.readline().strip()

            if line.startswith("READY:"):
                go_ip = self._detect_local_p2p_ip() or WINDOWS_GO_DEFAULT_IP
                creds = P2pGroupCredentials(
                    ssid=target_ssid,
                    passphrase=target_pass,
                    go_ip=go_ip,
                    port=port,
                    role="GO",
                )
                with self._lock:
                    self._ps_proc = proc
                    self._active_creds = creds
                logger.info("Wi-Fi Direct GO ready at %s (SSID=%s)", go_ip, target_ssid)
                return creds
            else:
                logger.warning("WinRT WiFiDirect output: %s; falling back to local IP", line)
                try:
                    proc.terminate()
                except Exception:
                    pass
        except Exception as exc:
            logger.warning("Failed to launch WinRT WiFiDirect publisher: %s", exc)

        # Fallback: return local IP + credentials so LAN / Virtual Wi-Fi Direct still works
        fallback_ip = self._detect_local_p2p_ip() or self._detect_primary_lan_ip()
        creds = P2pGroupCredentials(
            ssid=target_ssid,
            passphrase=target_pass,
            go_ip=fallback_ip,
            port=port,
            role="GO",
        )
        with self._lock:
            self._active_creds = creds
        return creds

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

        if creds:
            logger.info("Wi-Fi P2P link torn down (%s). Entered DEEP_IDLE state.", creds.ssid)

    def scan_nearby_p2p_networks(self) -> list[PeerInfo]:
        """
        Scan nearby Wi-Fi Direct (`DIRECT-*`) networks visible to the Windows WLAN adapter.
        Automatically decodes ConnectP2P friendly nicknames (`DIRECT-CP-...`) and derives
        their deterministic passphrase so the user can connect with a single click!
        """
        discovered: list[PeerInfo] = []
        own_ssid = self._active_creds.ssid if self._active_creds else None
        try:
            res = subprocess.run(
                ["netsh", "wlan", "show", "networks", "mode=bssid"],
                capture_output=True,
                timeout=5,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            raw_bytes = res.stdout or b""
            try:
                output = raw_bytes.decode("utf-8")
            except UnicodeDecodeError:
                output = raw_bytes.decode("gbk", errors="replace")

            seen_ssids: set[str] = set()
            for raw_line in output.splitlines():
                line = raw_line.strip()
                if line.startswith("SSID ") and ":" in line:
                    ssid = line.split(":", 1)[1].strip()
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
            logger.debug("WLAN scan skipped: %s", exc)
        return discovered

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
