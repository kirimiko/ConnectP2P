"""
Wire protocol framing, constants, and message builders for ConnectP2P.
Matches protocol/PROTOCOL.md and protocol/schema.json.
"""

from __future__ import annotations

import hashlib
import json
import os
import socket
import struct
import time
import uuid
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Any, Dict, Optional, Tuple

from Crypto.Cipher import AES
from Crypto.Util import Counter

MAGIC_BYTES = b"P2"
ENCRYPTED_HEADER_MAGIC = b"E1"
HEADER_STRUCT = struct.Struct("!2sBI")  # magic(2B), frame_type(1B), header_len(4B BE)

DEFAULT_APP_SECRET = "ConnectP2P-E2EE-AES256-Key-v1"
APP_AES_KEY: bytes = hashlib.sha256(DEFAULT_APP_SECRET.encode("utf-8")).digest()


def set_app_secret(secret: str) -> None:
    """Update the 256-bit application-layer encryption key derived from a shared secret."""
    global APP_AES_KEY
    APP_AES_KEY = hashlib.sha256(secret.strip().encode("utf-8")).digest()


def aes_gcm_encrypt(plaintext: bytes, nonce: Optional[bytes] = None) -> Tuple[bytes, bytes]:
    """Encrypt bytes using AES-256-GCM. Returns `(nonce_12B, ciphertext_with_16B_tag)`."""
    iv = nonce if (nonce is not None and len(nonce) == 12) else os.urandom(12)
    cipher = AES.new(APP_AES_KEY, AES.MODE_GCM, nonce=iv, mac_len=16)
    ct, tag = cipher.encrypt_and_digest(plaintext)
    return iv, ct + tag


def aes_gcm_decrypt(nonce: bytes, ciphertext_with_tag: bytes) -> bytes:
    """Decrypt and verify AES-256-GCM `ciphertext_with_16B_tag`."""
    if len(nonce) != 12 or len(ciphertext_with_tag) < 16:
        raise ValueError("Invalid AES-256-GCM nonce or tag length")
    ct = ciphertext_with_tag[:-16]
    tag = ciphertext_with_tag[-16:]
    cipher = AES.new(APP_AES_KEY, AES.MODE_GCM, nonce=nonce, mac_len=16)
    return cipher.decrypt_and_verify(ct, tag)


def create_aes_ctr_cipher(iv: bytes):
    """Create an AES-256-CTR stream cipher with a 128-bit Big-Endian counter (Java/Android compatible)."""
    if len(iv) != 16:
        raise ValueError("AES-256-CTR IV must be 16 bytes")
    ctr = Counter.new(128, initial_value=int.from_bytes(iv, "big"))
    return AES.new(APP_AES_KEY, AES.MODE_CTR, counter=ctr)

CONTROL_UDP_PORT = 45820
DATA_TCP_PORT = 45821
MULTICAST_GROUP = "239.255.77.88"

ANDROID_GO_DEFAULT_IP = "192.168.49.1"
WINDOWS_GO_DEFAULT_IP = "192.168.137.1"

BLE_SERVICE_UUID = "0000c0de-0000-1000-8000-00805f9b34fb"
BLE_CONTROL_CHAR_UUID = "0000c0df-0000-1000-8000-00805f9b34fb"
BLE_CLIPBOARD_CHAR_UUID = "0000c0e0-0000-1000-8000-00805f9b34fb"

MAX_INLINE_CLIPBOARD_BYTES = 16384  # <= 16KB syncs over low-power control channel without waking Wi-Fi P2P
BLE_MTU_CHUNK_SIZE = 480

P2P_IDLE_TEARDOWN_SECONDS = 15.0
SOCKET_BUFFER_SIZE = 262144  # 256 KB


class FrameType(IntEnum):
    HELLO = 1
    CLIPBOARD_SYNC = 2
    FILE_OFFER = 3
    FILE_ACCEPT = 4
    FILE_DATA = 5
    FILE_ACK = 6
    P2P_WAKE_REQUEST = 7
    P2P_GROUP_READY = 8
    P2P_TEARDOWN = 9
    PING_PONG = 10
    PAIR_REQUEST = 11
    PAIR_RESPONSE = 12


class PowerState(IntEnum):
    DEEP_IDLE = 0         # Wi-Fi P2P OFF, no locks held, event-driven control channel only
    P2P_HANDSHAKE = 1     # On-demand Wi-Fi P2P group creation & credential exchange
    P2P_BURST_ACTIVE = 2  # High-speed TCP file transfer active; auto-teardown after idle


@dataclass
class PeerInfo:
    device_id: str
    device_name: str
    platform: str  # "android" | "windows"
    ip: Optional[str] = None
    data_port: int = DATA_TCP_PORT
    ble_address: Optional[str] = None
    last_seen: float = field(default_factory=time.time)
    p2p_ssid: Optional[str] = None
    p2p_passphrase: Optional[str] = None
    p2p_go_ip: Optional[str] = None
    discovery_source: str = "lan"  # "lan" | "p2p_scan" | "ble"
    is_connected: bool = False


def derive_p2p_passphrase(ssid: str) -> str:
    """
    Derive a deterministic 12-char WPA2 passphrase from a ConnectP2P SSID (`DIRECT-CP-...`).
    Allows Android <-> Windows and Android <-> Android to auto-connect after scanning
    without manually typing a password.
    """
    normalized = ssid.strip()
    return hashlib.sha256(f"ConnectP2P-P2PKey-v1:{normalized}".encode("utf-8")).hexdigest()[:12]


def encode_p2p_ssid(device_name: str) -> str:
    """
    Encode a friendly device name (including Chinese/Unicode) into an IEEE 802.11 SSID (<= 32 bytes)
    that strictly matches Android WifiP2pConfig's `^DIRECT-[a-zA-Z0-9]{2}.*` requirement.
    - Pure ASCII name: `DIRECT-CP-N-<CleanAscii[:20]>`
    - Unicode/Chinese name: `DIRECT-CP-H-<Utf8Hex[:20]>`
    """
    cleaned = device_name.strip().replace(" ", "-")
    if not cleaned:
        cleaned = "Device"

    if all(c.isascii() and (c.isalnum() or c == "-") for c in cleaned):
        return f"DIRECT-CP-N-{cleaned[:20]}"

    # Truncate UTF-8 cleanly at character boundaries up to 10 bytes (20 hex chars)
    utf8_bytes = bytearray()
    for ch in device_name.strip():
        encoded_ch = ch.encode("utf-8")
        if len(utf8_bytes) + len(encoded_ch) > 10:
            break
        utf8_bytes.extend(encoded_ch)

    hex_part = bytes(utf8_bytes).hex().upper() or "444556"
    return f"DIRECT-CP-H-{hex_part}"


def decode_p2p_ssid(ssid: str) -> Tuple[str, bool]:
    """
    Decode a Wi-Fi Direct SSID into `(friendly_device_name, is_connect_p2p_auto)`.
    If `is_connect_p2p_auto` is True, `derive_p2p_passphrase(ssid)` can be used for 0-input connect.
    """
    s = ssid.strip()
    if s.startswith("DIRECT-CP-N-"):
        name = s[len("DIRECT-CP-N-") :] or s
        return name, True
    if s.startswith("DIRECT-CP-H-"):
        hex_part = s[len("DIRECT-CP-H-") :]
        try:
            name = bytes.fromhex(hex_part).decode("utf-8", errors="replace")
            return (name or s), True
        except ValueError:
            return s, True
    if s.startswith("DIRECT-"):
        parts = s.split("-", 2)
        if len(parts) == 3 and parts[2]:
            return parts[2], False
    return s, False


def compute_clip_hash(content: bytes) -> str:
    """Compute a 16-hex-char SHA-256 prefix used for clipboard loop suppression."""
    return hashlib.sha256(content).hexdigest()[:16]


def compute_file_sha256(file_path: str, chunk_size: int = SOCKET_BUFFER_SIZE) -> str:
    """Compute full SHA-256 hex digest of a file."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _encrypt_header_dict(header_dict: Dict[str, Any]) -> bytes:
    raw_json = json.dumps(header_dict, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    h_nonce, enc_hdr = aes_gcm_encrypt(raw_json)
    return ENCRYPTED_HEADER_MAGIC + h_nonce + enc_hdr


def _decrypt_header_bytes(header_bytes: bytes) -> Dict[str, Any]:
    if header_bytes.startswith(ENCRYPTED_HEADER_MAGIC):
        if len(header_bytes) < 2 + 12 + 16:
            raise ValueError("Truncated encrypted header")
        h_nonce = header_bytes[2:14]
        enc_hdr = header_bytes[14:]
        raw_json = aes_gcm_decrypt(h_nonce, enc_hdr)
        return json.loads(raw_json.decode("utf-8"))
    # Backward-compatible fallback for plain JSON headers
    return json.loads(header_bytes.decode("utf-8"))


def decrypt_payload_if_needed(header: Dict[str, Any], wire_payload: bytes) -> bytes:
    """Decrypt inline payload if `enc_payload_nonce` is present in header."""
    nonce_hex = header.get("enc_payload_nonce")
    if nonce_hex and wire_payload:
        return aes_gcm_decrypt(bytes.fromhex(str(nonce_hex)), wire_payload)
    return wire_payload


def encode_frame(
    frame_type: FrameType,
    header_dict: Dict[str, Any],
    payload: bytes = b"",
) -> bytes:
    """
    Encode an application-layer AES-256-GCM encrypted ConnectP2P frame:
    [Magic 'P2' (2B)][FrameType (1B)][HeaderLen (4B BE)][AES-GCM Header ('E1'+12B Nonce+Cipher+16B Tag)][AES-GCM Payload]
    """
    header_copy = dict(header_dict)
    if payload:
        p_nonce, wire_payload = aes_gcm_encrypt(payload)
        header_copy["enc_payload_nonce"] = p_nonce.hex()
    else:
        wire_payload = b""
    header_copy["payload_size"] = len(wire_payload)
    header_bytes = _encrypt_header_dict(header_copy)
    prefix = HEADER_STRUCT.pack(MAGIC_BYTES, int(frame_type), len(header_bytes))
    if wire_payload:
        return prefix + header_bytes + wire_payload
    return prefix + header_bytes


def encode_stream_header_only(
    frame_type: FrameType,
    header_dict: Dict[str, Any],
    stream_payload_size: int,
) -> bytes:
    """Encode an AES-256-GCM encrypted header for a streaming frame (`FILE_DATA`)."""
    header_copy = dict(header_dict)
    header_copy["payload_size"] = stream_payload_size
    header_bytes = _encrypt_header_dict(header_copy)
    return HEADER_STRUCT.pack(MAGIC_BYTES, int(frame_type), len(header_bytes)) + header_bytes


def decode_frame_from_bytes(data: bytes) -> Tuple[FrameType, Dict[str, Any], bytes]:
    """Decode and AES-256-GCM verify a complete in-memory frame (UDP/BLE control & clipboard packets)."""
    if len(data) < HEADER_STRUCT.size:
        raise ValueError(f"Frame too short: {len(data)} bytes")
    magic, ftype_raw, header_len = HEADER_STRUCT.unpack_from(data, 0)
    if magic != MAGIC_BYTES:
        raise ValueError(f"Invalid magic bytes: {magic!r}")
    ftype = FrameType(ftype_raw)
    header_end = HEADER_STRUCT.size + header_len
    if len(data) < header_end:
        raise ValueError(f"Truncated header: expected {header_end}, got {len(data)}")
    header = _decrypt_header_bytes(data[HEADER_STRUCT.size:header_end])
    payload_size = int(header.get("payload_size", 0))
    payload_end = header_end + payload_size
    if len(data) < payload_end:
        raise ValueError(f"Truncated payload: expected {payload_size} bytes")
    wire_payload = data[header_end:payload_end]
    payload = decrypt_payload_if_needed(header, wire_payload)
    header["payload_size"] = len(payload)
    return ftype, header, payload


def recv_exact(sock: socket.socket, n: int) -> bytes:
    """Read exactly n bytes from a blocking/timeout TCP socket."""
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"Socket closed while reading ({len(buf)}/{n} bytes)")
        buf.extend(chunk)
    return bytes(buf)


def read_frame_header_from_socket(sock: socket.socket) -> Tuple[FrameType, Dict[str, Any]]:
    """
    Read the 7-byte fixed prefix and AES-256-GCM encrypted JSON header from a TCP socket.
    Caller can then read `header['payload_size']` bytes directly (e.g., streaming to disk).
    """
    prefix = recv_exact(sock, HEADER_STRUCT.size)
    magic, ftype_raw, header_len = HEADER_STRUCT.unpack(prefix)
    if magic != MAGIC_BYTES:
        raise ValueError(f"Invalid magic bytes on TCP stream: {magic!r}")
    if header_len > 10 * 1024 * 1024:
        raise ValueError(f"Unreasonable header length: {header_len}")
    header_bytes = recv_exact(sock, header_len)
    header = _decrypt_header_bytes(header_bytes)
    return FrameType(ftype_raw), header


def chunk_ble_payload(frame_bytes: bytes, mtu_chunk: int = BLE_MTU_CHUNK_SIZE) -> list[bytes]:
    """
    Split a control/clipboard frame into BLE GATT MTU-safe chunks.
    Each chunk has a 4-byte header: [msg_id (1B)][chunk_idx (1B)][total_chunks (1B)][flags (1B)]
    """
    msg_id = uuid.uuid4().int & 0xFF
    usable = max(20, mtu_chunk - 4)
    total = max(1, (len(frame_bytes) + usable - 1) // usable)
    if total > 255:
        raise ValueError("Payload too large for BLE micro-channel; use Wi-Fi P2P burst channel")
    chunks: list[bytes] = []
    for idx in range(total):
        part = frame_bytes[idx * usable : (idx + 1) * usable]
        chunks.append(struct.pack("!BBBB", msg_id, idx, total, 0) + part)
    return chunks


class BleChunkReassembler:
    """Reassembles BLE GATT chunked frames back into a single ConnectP2P frame."""

    def __init__(self) -> None:
        self._buffers: Dict[int, Dict[int, bytes]] = {}
        self._totals: Dict[int, int] = {}

    def feed(self, chunk: bytes) -> Optional[bytes]:
        if len(chunk) < 4:
            return None
        msg_id, idx, total, _ = struct.unpack_from("!BBBB", chunk, 0)
        body = chunk[4:]
        if msg_id not in self._buffers or self._totals.get(msg_id) != total:
            self._buffers[msg_id] = {}
            self._totals[msg_id] = total
        self._buffers[msg_id][idx] = body
        if len(self._buffers[msg_id]) == total:
            assembled = b"".join(self._buffers[msg_id][i] for i in range(total))
            del self._buffers[msg_id]
            del self._totals[msg_id]
            return assembled
        return None
