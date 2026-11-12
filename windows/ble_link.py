"""
BLE central link from this PC to ConnectP2P phones (Windows BLE via `bleak` / WinRT).

The phone runs a GATT server that advertises BLE_SERVICE_UUID. This PC connects as the central,
subscribes to notifications on the control characteristic (phone -> PC) and writes frames to the
same characteristic (PC -> phone). Frames are split with `chunk_ble_payload` to the negotiated MTU
and reassembled per link with `BleChunkReassembler`.

BLE carries discovery, presence, pairing, small clipboard text and the signalling that brings the
Wi-Fi hotspot up on demand for bulk transfers.
"""

from __future__ import annotations

import asyncio
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable, Dict, List, Optional

from protocol import BLE_CONTROL_CHAR_UUID, BLE_SERVICE_UUID, BleChunkReassembler, chunk_ble_payload

try:
    from bleak import BleakClient, BleakScanner
except ImportError:  # pragma: no cover - optional dependency
    BleakClient = None  # type: ignore[assignment]
    BleakScanner = None  # type: ignore[assignment]

logger = logging.getLogger("ConnectP2P.BLE")

_SCAN_SECONDS = 5.0
_IDLE_RESCAN_SECONDS = 1.0
_LINKED_RESCAN_SECONDS = 30.0
# The phone's LE link outlives its app process, so "connected" alone proves nothing: if no frame came in
# for a while, ping; if the phone does not answer the ping, drop the link so it is rebuilt from scratch.
_PING_AFTER_SECONDS = 25.0
_PING_ANSWER_SECONDS = 12.0


class BleCentralLink:
    def __init__(
        self,
        on_frame: Callable[[bytes, str], None],
        on_link_change: Callable[[str, bool], None],
        ping_frame: Optional[Callable[[], bytes]] = None,
    ) -> None:
        self.on_frame = on_frame
        self.on_link_change = on_link_change
        self.ping_frame = ping_frame
        self._last_rx: Dict[str, float] = {}
        self._last_ping: Dict[str, float] = {}
        self._retry_after: Dict[str, float] = {}
        self._clients: Dict[str, Any] = {}
        self._write_locks: Dict[str, asyncio.Lock] = {}
        self._reassemblers: Dict[str, BleChunkReassembler] = {}
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._thread: Optional[threading.Thread] = None
        self._supervisor: Optional["asyncio.Task[None]"] = None
        self._keepalive: Optional["asyncio.Task[None]"] = None
        self._running = False
        # Single worker: frames from one link are handled in arrival order, off the BLE event loop.
        self._dispatch = ThreadPoolExecutor(max_workers=1, thread_name_prefix="BleDispatch")

    @property
    def available(self) -> bool:
        return BleakClient is not None

    def start(self) -> bool:
        if not self.available or self._running:
            return self._running
        self._running = True
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run_loop, name="ConnectP2PBle", daemon=True)
        self._thread.start()
        return True

    def stop(self) -> None:
        if not self._running or self._loop is None:
            return
        self._running = False
        fut = asyncio.run_coroutine_threadsafe(self._shutdown(), self._loop)
        try:
            fut.result(timeout=8.0)
        except Exception:
            pass
        self._loop.call_soon_threadsafe(self._loop.stop)
        if self._thread:
            self._thread.join(timeout=3.0)
        self._dispatch.shutdown(wait=False)

    def linked_addresses(self) -> List[str]:
        return [a for a, c in list(self._clients.items()) if c.is_connected]

    def is_linked(self, address: Optional[str]) -> bool:
        client = self._clients.get(address) if address else None
        return client is not None and client.is_connected

    def max_frame_bytes(self, address: Optional[str]) -> int:
        """Largest frame one chunked BLE message (255 chunks) can carry on this link; 0 if not linked."""
        if not self.is_linked(address):
            return 0
        return 255 * (self._chunk_size(address) - 4)  # type: ignore[arg-type]

    def send_frame(self, address: Optional[str], frame: bytes) -> bool:
        """Queue a frame for the phone at `address`. Returns False if not linked or the frame is too large."""
        if not address or self._loop is None or not self.is_linked(address):
            return False
        if len(frame) > self.max_frame_bytes(address):
            return False
        asyncio.run_coroutine_threadsafe(self._write(address, frame), self._loop)
        return True

    # ---------------- event loop side ----------------

    def _run_loop(self) -> None:
        assert self._loop is not None
        asyncio.set_event_loop(self._loop)
        self._supervisor = self._loop.create_task(self._supervise())
        self._keepalive = self._loop.create_task(self._keepalive_loop())
        try:
            self._loop.run_forever()
        finally:
            self._loop.close()

    def _chunk_size(self, address: str) -> int:
        client = self._clients.get(address)
        mtu = getattr(client, "mtu_size", 23) or 23
        return max(20, min(512, mtu - 3))

    async def _supervise(self) -> None:
        while self._running:
            try:
                started = asyncio.get_running_loop().time()
                found = await BleakScanner.discover(  # type: ignore[union-attr]
                    timeout=_SCAN_SECONDS, service_uuids=[BLE_SERVICE_UUID], return_adv=True
                )
                now = asyncio.get_running_loop().time()
                new = [
                    d for a, (d, _adv) in found.items()
                    if not self.is_linked(a) and self._retry_after.get(a, 0.0) <= now
                ]
                if new:
                    logger.info(
                        "BLE scan found %s in %.1fs",
                        ", ".join(d.address for d in new),
                        asyncio.get_running_loop().time() - started,
                    )
                for device in new:
                    if not self._running:
                        break
                    await self._connect(device)
            except Exception as exc:
                logger.info("BLE scan cycle failed: %s", exc)
            await asyncio.sleep(_LINKED_RESCAN_SECONDS if self._clients else _IDLE_RESCAN_SECONDS)

    async def _keepalive_loop(self) -> None:
        while self._running:
            await asyncio.sleep(4.0)
            now = asyncio.get_running_loop().time()
            for address, client in list(self._clients.items()):
                last_rx = self._last_rx.get(address, now)
                last_ping = self._last_ping.get(address, 0.0)
                if last_ping > last_rx and now - last_ping >= _PING_ANSWER_SECONDS:
                    logger.info("BLE link to %s did not answer a ping; reconnecting", address)
                    try:
                        await client.disconnect()
                    except Exception:
                        pass
                    self._handle_disconnected(address)
                elif now - last_rx >= _PING_AFTER_SECONDS and last_ping <= last_rx:
                    self._last_ping[address] = now
                    if self.ping_frame is not None:
                        await self._write(address, self.ping_frame())

    async def _connect(self, device: Any) -> None:
        address = device.address
        loop = asyncio.get_running_loop()

        def _disconnected(_client: Any) -> None:
            loop.call_soon_threadsafe(self._handle_disconnected, address)

        client = BleakClient(  # type: ignore[misc]
            device,
            disconnected_callback=_disconnected,
            winrt={"use_cached_services": False},
        )
        started = loop.time()
        try:
            await client.connect(timeout=15.0)
            self._reassemblers[address] = BleChunkReassembler()
            self._last_rx[address] = loop.time()
            self._clients[address] = client
            await client.start_notify(
                BLE_CONTROL_CHAR_UUID, lambda _char, data: self._handle_notify(address, bytes(data))
            )
            logger.info("BLE linked to %s in %.1fs (mtu=%s)", address, loop.time() - started, client.mtu_size)
            self._dispatch.submit(self._safe_call, self.on_link_change, address, True)
        except Exception as exc:
            logger.info("BLE connect to %s failed: %s", address, exc)
            # A linked phone keeps advertising under rotating random addresses, and connecting to those
            # fails after the full timeout; don't stall the scan loop on them.
            self._retry_after[address] = loop.time() + (300.0 if self._clients else 10.0)
            self._clients.pop(address, None)
            try:
                await client.disconnect()
            except Exception:
                pass

    def _handle_notify(self, address: str, data: bytes) -> None:
        reassembler = self._reassemblers.get(address)
        if reassembler is None:
            return
        if self._loop is not None:
            self._last_rx[address] = self._loop.time()
        frame = reassembler.feed(data)
        if frame is not None:
            self._dispatch.submit(self._safe_call, self.on_frame, frame, address)

    def _handle_disconnected(self, address: str) -> None:
        if self._clients.pop(address, None) is None:
            return
        self._reassemblers.pop(address, None)
        self._write_locks.pop(address, None)
        self._last_rx.pop(address, None)
        self._last_ping.pop(address, None)
        logger.info("BLE link to %s lost", address)
        self._dispatch.submit(self._safe_call, self.on_link_change, address, False)

    async def _write(self, address: str, frame: bytes) -> None:
        lock = self._write_locks.setdefault(address, asyncio.Lock())
        async with lock:
            client = self._clients.get(address)
            if client is None or not client.is_connected:
                return
            try:
                for chunk in chunk_ble_payload(frame, mtu_chunk=self._chunk_size(address)):
                    await client.write_gatt_char(BLE_CONTROL_CHAR_UUID, chunk, response=True)
            except Exception as exc:
                logger.warning("BLE write to %s failed: %s", address, exc)

    async def _shutdown(self) -> None:
        for task in (self._supervisor, self._keepalive):
            if task is None:
                continue
            task.cancel()
            try:
                await task
            except BaseException:
                pass
        for client in list(self._clients.values()):
            try:
                await client.disconnect()
            except Exception:
                pass
        self._clients.clear()

    @staticmethod
    def _safe_call(fn: Callable[..., None], *args: Any) -> None:
        try:
            fn(*args)
        except Exception as exc:
            logger.warning("BLE callback failed: %s", exc)
