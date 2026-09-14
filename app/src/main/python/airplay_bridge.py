"""AirPlay bridge for Android (Chaquopy): scan, pair, play_url, stop."""

from __future__ import annotations

import asyncio
import json
import threading
from pathlib import Path
from typing import Any, Optional

import pyatv
from pyatv.const import Protocol

from pyatv_patch import apply_playurl_patch

_CLIENT_NAME = "Beam"
_loop: Optional[asyncio.AbstractEventLoop] = None
_loop_thread: Optional[threading.Thread] = None
_pairing = None
_pin_event: Optional[asyncio.Event] = None
_pin_value: Optional[str] = None
_atv = None
_play_task = None
_devices: dict[str, Any] = {}
_storage_dir: Optional[Path] = None


def _ensure_loop() -> asyncio.AbstractEventLoop:
    global _loop, _loop_thread
    if _loop is not None:
        return _loop

    ready = threading.Event()

    def runner() -> None:
        global _loop
        _loop = asyncio.new_event_loop()
        asyncio.set_event_loop(_loop)
        ready.set()
        _loop.run_forever()

    _loop_thread = threading.Thread(target=runner, name="pyatv-loop", daemon=False)
    _loop_thread.start()
    ready.wait(timeout=10)
    assert _loop is not None
    apply_playurl_patch()
    return _loop


def _run(coro):
    loop = _ensure_loop()
    fut = asyncio.run_coroutine_threadsafe(coro, loop)
    return fut.result(timeout=120)


def init_storage(path: str) -> None:
    global _storage_dir
    _storage_dir = Path(path)
    _storage_dir.mkdir(parents=True, exist_ok=True)


def _creds_file() -> Path:
    assert _storage_dir is not None
    return _storage_dir / "credentials.json"


def _load_creds() -> dict:
    f = _creds_file()
    if not f.is_file():
        return {}
    try:
        return json.loads(f.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _save_creds(data: dict) -> None:
    _creds_file().write_text(json.dumps(data, indent=2), encoding="utf-8")


def has_credentials(identifier: str) -> bool:
    entry = _load_creds().get(identifier)
    if isinstance(entry, dict):
        return bool(entry.get("airplay"))
    return bool(entry)


def scan(timeout: float = 8.0, hosts_json: str = "[]") -> str:
    """Return JSON list of devices [{name,address,identifier,model,paired}].

    hosts_json: optional JSON array of IP hints from Android NSD.
    """

    def _has_protocol(conf, protocol: Protocol) -> bool:
        try:
            return any(getattr(s, "protocol", None) == protocol for s in conf.services)
        except Exception:
            return False

    def _keep(conf) -> bool:
        if _has_protocol(conf, Protocol.AirPlay):
            return True
        if _has_protocol(conf, Protocol.RAOP):
            return True
        if _has_protocol(conf, Protocol.Companion):
            return True
        name = conf.name or ""
        if "Apple TV" in name or "AppleTV" in name.replace(" ", ""):
            return True
        try:
            info = conf.device_info
            model = str(getattr(info, "model_str", "") or getattr(info, "raw_model", "") or "")
            if "AppleTV" in model or "Apple TV" in model:
                return True
        except Exception:
            pass
        # Keep anything from an explicit host probe
        return False

    async def _scan():
        global _devices
        loop = _ensure_loop()
        confs = list(await pyatv.scan(loop, timeout=timeout))

        # Targeted probes for NSD / manual IPs (critical on Android without multicast)
        try:
            hints = json.loads(hosts_json) if hosts_json else []
        except Exception:
            hints = []
        hint_ips = []
        for h in hints:
            if isinstance(h, str) and h.strip():
                hint_ips.append(h.strip())
            elif isinstance(h, dict) and h.get("host"):
                hint_ips.append(str(h["host"]).strip())
        # de-dupe
        seen_ip = set()
        uniq_ips = []
        for ip in hint_ips:
            if ip not in seen_ip:
                seen_ip.add(ip)
                uniq_ips.append(ip)

        for ip in uniq_ips:
            try:
                more = await pyatv.scan(loop, hosts=[ip], timeout=min(4.0, timeout))
                confs.extend(more)
            except Exception:
                pass

        out = []
        _devices = {}
        for conf in confs:
            if not _keep(conf) and str(conf.address) not in seen_ip:
                # Still keep host-probed devices even if protocol filter is odd
                if str(conf.address) not in uniq_ips:
                    continue
            ident = conf.identifier or str(conf.address)
            if ident in _devices:
                continue
            name = conf.name or "Unbekannt"
            model = "Apple TV"
            try:
                info = conf.device_info
                if info and getattr(info, "model_str", None):
                    model = info.model_str
            except Exception:
                pass
            item = {
                "name": name,
                "address": str(conf.address),
                "identifier": ident,
                "model": model,
                "paired": has_credentials(ident),
            }
            _devices[ident] = conf
            out.append(item)
        return json.dumps(out)

    return _run(_scan())

def pair_start(identifier: str) -> str:
    async def _begin():
        global _pairing, _pin_event, _pin_value
        loop = _ensure_loop()
        _pin_event = asyncio.Event()
        _pin_value = None
        conf = _devices.get(identifier)
        if conf is None:
            found = await pyatv.scan(loop, identifier=identifier, timeout=5)
            if not found:
                raise RuntimeError("Gerät nicht gefunden — bitte erneut suchen")
            conf = found[0]
            _devices[identifier] = conf
        try:
            pairing = await pyatv.pair(conf, Protocol.AirPlay, loop, name=_CLIENT_NAME)
        except TypeError:
            pairing = await pyatv.pair(conf, Protocol.AirPlay, loop)
        _pairing = pairing
        await pairing.begin()
        return "PIN_REQUIRED" if pairing.device_provides_pin else "ENTER_PIN"

    return _run(_begin())


def pair_pin(pin: str) -> None:
    global _pin_value
    digits = "".join(c for c in pin.strip() if c.isdigit())
    if not digits:
        raise ValueError("PIN muss Ziffern enthalten")
    _pin_value = digits
    loop = _ensure_loop()
    ev = _pin_event
    if ev is not None:
        loop.call_soon_threadsafe(ev.set)


def pair_finish(identifier: str, name: str = "") -> str:
    async def _finish():
        global _pairing
        pairing = _pairing
        if pairing is None:
            raise RuntimeError("Kein Pairing aktiv")
        ev = _pin_event
        if ev is not None and not ev.is_set():
            await ev.wait()
        if _pin_value:
            pairing.pin(_pin_value)
        await pairing.finish()
        try:
            if not pairing.has_paired:
                raise RuntimeError(
                    "Pairing failed. On the Apple TV go to "
                    "AirPlay and HomeKit → Allow Access = Anyone on the Same Network"
                )
            creds = pairing.service.credentials
            if not creds:
                raise RuntimeError("Keine Credentials")
            data = _load_creds()
            data[identifier] = {"airplay": creds, "name": name}
            _save_creds(data)
            return "OK"
        finally:
            await pairing.close()
            _pairing = None

    return _run(_finish())


def play_url(identifier: str, url: str, media_type: str = "video") -> str:
    async def _play():
        global _atv, _play_task
        apply_playurl_patch()
        from pyatv_patch import set_pending_media_type

        set_pending_media_type(media_type)
        loop = _ensure_loop()
        data = _load_creds()
        entry = data.get(identifier)
        creds = entry.get("airplay") if isinstance(entry, dict) else entry
        if not creds:
            raise RuntimeError("Nicht gekoppelt")
        found = await pyatv.scan(loop, identifier=identifier, timeout=5)
        conf = found[0] if found else _devices.get(identifier)
        if conf is None:
            raise RuntimeError("Gerät nicht gefunden")
        conf.set_credentials(Protocol.AirPlay, creds)
        if _atv is not None:
            try:
                await _atv.close()
            except Exception:
                pass
        _atv = await pyatv.connect(conf, loop)
        _play_task = asyncio.create_task(_atv.stream.play_url(url))
        # Give play_url a moment; live streams stay open
        await asyncio.sleep(2.0)
        if _play_task.done():
            exc = _play_task.exception()
            if exc is not None:
                raise RuntimeError(str(exc))
        return "OK"

    return _run(_play())


def stop_playback() -> str:
    async def _stop():
        global _atv, _play_task
        task = _play_task
        if task is not None and not task.done():
            task.cancel()
            try:
                await task
            except Exception:
                pass
        _play_task = None
        if _atv is not None:
            try:
                await _atv.remote_control.stop()
            except Exception:
                pass
            try:
                await _atv.close()
            except Exception:
                pass
            _atv = None
        return "OK"

    try:
        return _run(_stop())
    except Exception:
        return "OK"
