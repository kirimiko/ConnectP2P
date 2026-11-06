"""
Event-driven Windows Clipboard Monitor & Loop-Safe Clipboard Writer.

Key low-power design:
- Uses Win32 `AddClipboardFormatListener` on a message-only window (`HWND_MESSAGE`)
  to receive `WM_CLIPBOARDUPDATE` kernel notifications instead of polling in a timer loop!
- Deduplicates clipboard updates using a 16-hex-char SHA-256 digest (`compute_clip_hash`)
  so remote clipboard writes never bounce back to the sender.
"""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wintypes
import sys
import threading
import time
from typing import Callable, Optional

from protocol import compute_clip_hash

CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002
WM_CLIPBOARDUPDATE = 0x031D
WM_QUIT = 0x0012
HWND_MESSAGE = wintypes.HWND(-3)

if sys.platform == "win32":
    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32

    user32.OpenClipboard.argtypes = [wintypes.HWND]
    user32.OpenClipboard.restype = wintypes.BOOL
    user32.CloseClipboard.argtypes = []
    user32.CloseClipboard.restype = wintypes.BOOL
    user32.EmptyClipboard.argtypes = []
    user32.EmptyClipboard.restype = wintypes.BOOL
    user32.GetClipboardData.argtypes = [wintypes.UINT]
    user32.GetClipboardData.restype = wintypes.HANDLE
    user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
    user32.SetClipboardData.restype = wintypes.HANDLE
    user32.IsClipboardFormatAvailable.argtypes = [wintypes.UINT]
    user32.IsClipboardFormatAvailable.restype = wintypes.BOOL
    user32.AddClipboardFormatListener.argtypes = [wintypes.HWND]
    user32.AddClipboardFormatListener.restype = wintypes.BOOL
    user32.RemoveClipboardFormatListener.argtypes = [wintypes.HWND]
    user32.RemoveClipboardFormatListener.restype = wintypes.BOOL

    kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
    kernel32.GlobalAlloc.restype = wintypes.HGLOBAL
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = ctypes.c_void_p
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalUnlock.restype = wintypes.BOOL
    kernel32.GlobalFree.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalFree.restype = wintypes.HGLOBAL


def get_clipboard_text() -> Optional[str]:
    """Read UTF-16LE text from Windows clipboard with retry on transient lock contention."""
    if sys.platform != "win32":
        return None
    if not user32.IsClipboardFormatAvailable(CF_UNICODETEXT):
        return None
    for _ in range(5):
        if user32.OpenClipboard(None):
            try:
                handle = user32.GetClipboardData(CF_UNICODETEXT)
                if not handle:
                    return None
                ptr = kernel32.GlobalLock(handle)
                if not ptr:
                    return None
                try:
                    return ctypes.wstring_at(ptr)
                finally:
                    kernel32.GlobalUnlock(handle)
            finally:
                user32.CloseClipboard()
        time.sleep(0.02)
    return None


def set_clipboard_text(text: str) -> bool:
    """Write UTF-16LE text to Windows clipboard with retry on transient lock contention."""
    if sys.platform != "win32":
        return False
    encoded = text.encode("utf-16-le") + b"\x00\x00"
    for _ in range(5):
        if user32.OpenClipboard(None):
            try:
                user32.EmptyClipboard()
                h_mem = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(encoded))
                if not h_mem:
                    return False
                ptr = kernel32.GlobalLock(h_mem)
                if not ptr:
                    kernel32.GlobalFree(h_mem)
                    return False
                ctypes.memmove(ptr, encoded, len(encoded))
                kernel32.GlobalUnlock(h_mem)
                if not user32.SetClipboardData(CF_UNICODETEXT, h_mem):
                    kernel32.GlobalFree(h_mem)
                    return False
                return True
            finally:
                user32.CloseClipboard()
        time.sleep(0.02)
    return False


class ClipboardSyncController:
    """
    Manages bidirectional clipboard synchronization with:
    1. Loop suppression (ignores clipboard updates that match `last_applied_hash`)
    2. Event-driven Win32 `WM_CLIPBOARDUPDATE` listener (zero CPU polling when idle)
    """

    def __init__(self, on_local_clipboard_change: Callable[[str, str], None]) -> None:
        """
        :param on_local_clipboard_change: callback(text, clip_hash) invoked when local user copies new text
        """
        self._on_change = on_local_clipboard_change
        self._lock = threading.Lock()
        self._last_hash: Optional[str] = None
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._thread_id: Optional[int] = None

        # Seed initial clipboard hash so startup doesn't immediately broadcast stale clipboard
        initial = get_clipboard_text()
        if initial:
            self._last_hash = compute_clip_hash(initial.encode("utf-8"))

    @property
    def last_hash(self) -> Optional[str]:
        with self._lock:
            return self._last_hash

    def apply_remote_clipboard(self, text: str, remote_clip_hash: Optional[str] = None) -> bool:
        """
        Apply clipboard text received from a remote Android/Windows peer.
        Records the hash BEFORE writing to the OS clipboard so the resulting
        WM_CLIPBOARDUPDATE event is suppressed.
        """
        content_bytes = text.encode("utf-8")
        digest = remote_clip_hash or compute_clip_hash(content_bytes)
        with self._lock:
            if digest == self._last_hash:
                return False
            self._last_hash = digest
        return set_clipboard_text(text)

    def check_and_notify_now(self) -> Optional[Tuple[str, str]]:
        """
        Inspect current OS clipboard and fire callback if changed and not suppressed.
        Can be called from WM_CLIPBOARDUPDATE handler or manually.
        """
        text = get_clipboard_text()
        if not text:
            return None
        digest = compute_clip_hash(text.encode("utf-8"))
        with self._lock:
            if digest == self._last_hash:
                return None
            self._last_hash = digest
        self._on_change(text, digest)
        return text, digest

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(
            target=self._win32_message_loop,
            name="Win32ClipboardListener",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._running = False
        if self._thread_id and sys.platform == "win32":
            user32.PostThreadMessageW(self._thread_id, WM_QUIT, 0, 0)
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2.0)

    def _win32_message_loop(self) -> None:
        """
        Creates a lightweight message-only window (`HWND_MESSAGE`) and registers
        `AddClipboardFormatListener`. Blocks in `GetMessageW` consuming 0% CPU until
        the user copies something into the clipboard.
        """
        if sys.platform != "win32":
            return

        self._thread_id = kernel32.GetCurrentThreadId()

        WNDPROCTYPE = ctypes.WINFUNCTYPE(
            ctypes.c_long,
            wintypes.HWND,
            wintypes.UINT,
            wintypes.WPARAM,
            wintypes.LPARAM,
        )

        def wnd_proc(hwnd: int, msg: int, wparam: int, lparam: int) -> int:
            if msg == WM_CLIPBOARDUPDATE:
                try:
                    self.check_and_notify_now()
                except Exception:
                    pass
                return 0
            return user32.DefWindowProcW(hwnd, msg, wparam, lparam)

        wnd_proc_cb = WNDPROCTYPE(wnd_proc)

        class WNDCLASSEXW(ctypes.Structure):
            _fields_ = [
                ("cbSize", wintypes.UINT),
                ("style", wintypes.UINT),
                ("lpfnWndProc", WNDPROCTYPE),
                ("cbClsExtra", ctypes.c_int),
                ("cbWndExtra", ctypes.c_int),
                ("hInstance", wintypes.HINSTANCE),
                ("hIcon", wintypes.HICON),
                ("hCursor", wintypes.HANDLE),
                ("hbrBackground", wintypes.HBRUSH),
                ("lpszMenuName", wintypes.LPCWSTR),
                ("lpszClassName", wintypes.LPCWSTR),
                ("hIconSm", wintypes.HICON),
            ]

        user32.DefWindowProcW.argtypes = [
            wintypes.HWND,
            wintypes.UINT,
            wintypes.WPARAM,
            wintypes.LPARAM,
        ]
        user32.DefWindowProcW.restype = ctypes.c_long

        h_instance = kernel32.GetModuleHandleW(None)
        class_name = f"ConnectP2PClipWnd_{ id(self) }"

        wnd_class = WNDCLASSEXW()
        wnd_class.cbSize = ctypes.sizeof(WNDCLASSEXW)
        wnd_class.lpfnWndProc = wnd_proc_cb
        wnd_class.hInstance = h_instance
        wnd_class.lpszClassName = class_name

        user32.RegisterClassExW(ctypes.byref(wnd_class))
        hwnd = user32.CreateWindowExW(
            0,
            class_name,
            "ConnectP2PClipboardListener",
            0,
            0,
            0,
            0,
            0,
            HWND_MESSAGE,
            None,
            h_instance,
            None,
        )

        if not hwnd:
            return

        user32.AddClipboardFormatListener(hwnd)
        try:
            msg = wintypes.MSG()
            while self._running:
                ret = user32.GetMessageW(ctypes.byref(msg), None, 0, 0)
                if ret <= 0:
                    break
                user32.TranslateMessage(ctypes.byref(msg))
                user32.DispatchMessageW(ctypes.byref(msg))
        finally:
            user32.RemoveClipboardFormatListener(hwnd)
            user32.DestroyWindow(hwnd)
