"""Monkeypatch pyatv for modern tvOS AirPlay URL playback.

Stock pyatv 0.18 still uses legacy POST /play + GET /playback-info. On recent
tvOS that path returns HTTP 500 and often never starts media. This patch mirrors
the community fix (queue commands via POST /command + event-channel state).
"""

from __future__ import annotations

import asyncio
import logging
import plistlib
from typing import Any, Callable, Dict, List, Optional, cast
from uuid import uuid4

from pyatv import exceptions
from pyatv.auth.hap_channel import setup_channel
from pyatv.protocols.airplay import channels as airplay_channels
from pyatv.protocols.airplay import player as airplay_player
from pyatv.protocols.airplay.channels import EventChannel
from pyatv.protocols.airplay.player import AirPlayPlayer
from pyatv.protocols.airplay.utils import decode_plist_body
from pyatv.protocols.raop.protocols import airplayv2 as airplayv2_mod
from pyatv.protocols.raop.protocols.airplayv2 import AirPlayV2
from pyatv.support.http import HttpResponse, decode_bplist_from_body
from pyatv.support.rtsp import HTTP_PROTOCOL

_LOGGER = logging.getLogger(__name__)
_APPLIED = False

COMMAND_USER_AGENT = "AirPlay/870.14.1"
REMOTE_CONTROL_CLIENT_TYPE = "A6B27562-B43A-4F2D-B75F-82391E250194"
EVENT_WAIT_RETRIES = 30

# Set by bridge before play_url (video | music | file)
_pending_media_type: str = "video"


def set_pending_media_type(media_type: str) -> None:
    global _pending_media_type
    _pending_media_type = media_type or "video"


def apply_playurl_patch() -> None:
    """Idempotent runtime patch of pyatv AirPlay URL playback."""
    global _APPLIED
    if _APPLIED:
        return
    _patch_event_channel()
    _patch_airplay_v2()
    _patch_airplay_player()
    _APPLIED = True
    _LOGGER.info("pyatv play_url patch applied (modern /command + events)")


def _patch_event_channel() -> None:
    original_init = EventChannel.__init__
    original_handle = EventChannel.handle_received

    def __init__(self, output_key: bytes, input_key: bytes) -> None:  # type: ignore[no-untyped-def]
        original_init(self, output_key, input_key)
        self._event_listener: Optional[Callable[[dict], None]] = None

    def set_event_listener(self, listener: Callable[[dict], None]) -> None:  # type: ignore[no-untyped-def]
        self._event_listener = listener

    def _process_event(self, request) -> None:  # type: ignore[no-untyped-def]
        payload = decode_plist_body(request.body)
        if not isinstance(payload, dict):
            return
        params = payload.get("params")
        nested = params.get("data") if isinstance(params, dict) else None
        if nested is not None:
            payload = decode_plist_body(nested)
        if isinstance(payload, dict) and self._event_listener:
            self._event_listener(payload)

    def handle_received(self) -> None:  # type: ignore[no-untyped-def]
        self.buffer: bytes
        while self.buffer:
            try:
                request, _, self.buffer = self.parse_request(self.buffer)
                if request is None:
                    break
                self._process_event(request)
                headers = {"Content-Length": "0", "Audio-Latency": "0"}
                if "Server" in request.headers:
                    headers["Server"] = request.headers["Server"]
                if "CSeq" in request.headers:
                    headers["CSeq"] = request.headers["CSeq"]
                self.send(
                    self.format_response(
                        HttpResponse(
                            request.protocol,
                            request.version,
                            200,
                            "OK",
                            headers,
                            b"",
                        )
                    )
                )
            except Exception:
                _LOGGER.exception("Failed to handle message on event channel")
                break

    EventChannel.__init__ = __init__  # type: ignore[method-assign]
    EventChannel.set_event_listener = set_event_listener  # type: ignore[attr-defined]
    EventChannel._process_event = _process_event  # type: ignore[attr-defined]
    EventChannel.handle_received = handle_received  # type: ignore[method-assign]
    # Keep reference so import side-effects are clear
    airplay_channels.EventChannel = EventChannel


def _patch_airplay_v2() -> None:
    original_setup_base = AirPlayV2._setup_base
    original_teardown = AirPlayV2.teardown

    async def _setup_base(self, timing_server_port: int) -> None:  # type: ignore[no-untyped-def]
        if not hasattr(self, "_session_id"):
            self._session_id = str(uuid4()).upper()
            sender_bytes = bytearray(uuid4().bytes[:6])
            sender_bytes[0] = (sender_bytes[0] & 0xFC) | 0x02
            self._sender_device_id = ":".join(f"{b:02X}" for b in sender_bytes)
            self._stream_id: Optional[int] = None
            self._playback_state: Optional[str] = None
            self._playback_state_changed = asyncio.Event()
            self.event_protocol: Optional[EventChannel] = None

        from pyatv.protocols.airplay.auth import verify_connection

        self._verifier = await verify_connection(
            self.context.credentials, self.rtsp.connection
        )

        setup_resp = await self.rtsp.setup(
            body={
                "deviceID": self._sender_device_id,
                "sessionUUID": self._session_id,
                "sessionCorrelationUUID": str(uuid4()).upper(),
                "timingPort": timing_server_port,
                "timingProtocol": "NTP",
                "isMultiSelectAirPlay": True,
                "groupContainsGroupLeader": False,
                "macAddress": self._sender_device_id,
                "model": "iPhone14,3",
                "name": "Beam",
                "osBuildVersion": "20F66",
                "osName": "iPhone OS",
                "osVersion": "16.5",
                "senderSupportsRelay": False,
                "sourceVersion": "690.7.1",
                "statsCollectionEnabled": False,
            }
        )
        resp = decode_bplist_from_body(setup_resp)
        event_port = resp.get("eventPort", 0)

        retries = 5
        transport = None
        protocol = None
        while transport is None:
            try:
                transport, protocol = await setup_channel(
                    EventChannel,
                    self._verifier,
                    self.rtsp.connection.remote_ip,
                    event_port,
                    airplayv2_mod.EVENTS_SALT,
                    airplayv2_mod.EVENTS_READ_INFO,
                    airplayv2_mod.EVENTS_WRITE_INFO,
                )
            except (ConnectionRefusedError, OSError):
                retries -= 1
                if retries == 0:
                    raise
                await asyncio.sleep(1.0)

        self.event_channel = transport
        self.event_protocol = cast(EventChannel, protocol)
        if self.event_protocol is not None and hasattr(self.event_protocol, "set_event_listener"):
            self.event_protocol.set_event_listener(self._handle_event)

    def _handle_event(self, event: Dict[str, Any]) -> None:  # type: ignore[no-untyped-def]
        if event.get("type") != "playbackState":
            return
        params = event.get("params")
        state = params.get("playbackState") if isinstance(params, dict) else None
        if state is None:
            state = event.get("name")
        if isinstance(state, str):
            normalized = state.lower()
            if normalized != self._playback_state:
                self._playback_state = normalized
                self._playback_state_changed.set()

    async def _setup_url_stream(self) -> None:  # type: ignore[no-untyped-def]
        response = await self.rtsp.setup(
            body={
                "streams": [
                    {
                        "clientUUID": str(uuid4()).upper(),
                        "clientTypeUUID": REMOTE_CONTROL_CLIENT_TYPE,
                        "channelID": f"{self._sender_device_id}-RCS-1",
                        "controlType": 1,
                        "type": 130,
                    }
                ]
            }
        )
        payload = decode_bplist_from_body(response)
        streams = payload.get("streams", [])
        if not streams or "streamID" not in streams[0]:
            raise exceptions.ProtocolError("missing URL control stream in SETUP response")
        self._stream_id = int(streams[0]["streamID"])

    async def _send_url_command(self, command: Dict[str, Any]) -> HttpResponse:  # type: ignore[no-untyped-def]
        if self._stream_id is None:
            raise exceptions.InvalidStateError("URL control stream not set up")
        body = {
            "params": {
                "data": plistlib.dumps(
                    command,
                    fmt=plistlib.FMT_BINARY,  # pylint: disable=no-member
                    sort_keys=False,
                )
            }
        }
        return await self.rtsp.exchange(
            "POST",
            uri="/command",
            protocol=HTTP_PROTOCOL,
            headers={
                "User-Agent": COMMAND_USER_AGENT,
                "X-Apple-ProtocolVersion": "1",
                "X-Apple-Session-ID": self._session_id,
                "X-Apple-StreamID": self._stream_id,
            },
            body=body,
            allow_error=True,
        )

    async def play_url(  # type: ignore[no-untyped-def]
        self, timing_server_port: int, url: str, position: float = 0.0
    ) -> HttpResponse:
        if self._verifier is None:
            await self._setup_base(timing_server_port)
        await self.start_feedback()
        try:
            await self.rtsp.info()
        except Exception:
            pass
        await self.rtsp.record()
        await self._setup_url_stream()

        item = {
            "uuid": str(uuid4()).upper(),
            "mediaType": _pending_media_type or "video",
            "Content-Location": url,
            "Start-Position-Seconds": position,
        }
        commands: List[Dict[str, Any]] = [
            {"type": "insertPlayQueueItem", "item": item},
            {
                "type": "setProperty",
                "value": True,
                "property": "isInterestedInDateRange",
                "item": {"uuid": item["uuid"]},
            },
            {"type": "setProperty", "value": 1, "property": "actionAtItemEnd"},
            {"type": "setRate", "rate": 1.0},
        ]

        response: Optional[HttpResponse] = None
        for command in commands:
            response = await self._send_url_command(command)
            _LOGGER.debug("AirPlay command %s -> %s", command.get("type"), response.code)
            if not 200 <= response.code < 300:
                return response
        assert response is not None
        return response

    def teardown(self) -> None:  # type: ignore[no-untyped-def]
        original_teardown(self)
        self.event_protocol = None

    @property
    def playback_state(self) -> Optional[str]:  # type: ignore[no-untyped-def]
        return getattr(self, "_playback_state", None)

    async def wait_for_playback_state_change(  # type: ignore[no-untyped-def]
        self, previous_state: Optional[str], timeout: float
    ) -> Optional[str]:
        if not hasattr(self, "_playback_state_changed"):
            return getattr(self, "_playback_state", None)
        self._playback_state_changed.clear()
        if self._playback_state != previous_state:
            return self._playback_state
        try:
            await asyncio.wait_for(self._playback_state_changed.wait(), timeout)
        except asyncio.TimeoutError:
            pass
        return self._playback_state

    @property
    def supports_playback_state(self) -> bool:  # type: ignore[no-untyped-def]
        return True

    AirPlayV2._setup_base = _setup_base  # type: ignore[method-assign]
    AirPlayV2._handle_event = _handle_event  # type: ignore[attr-defined]
    AirPlayV2._setup_url_stream = _setup_url_stream  # type: ignore[attr-defined]
    AirPlayV2._send_url_command = _send_url_command  # type: ignore[attr-defined]
    AirPlayV2.play_url = play_url  # type: ignore[method-assign]
    AirPlayV2.teardown = teardown  # type: ignore[method-assign]
    AirPlayV2.playback_state = playback_state  # type: ignore[assignment]
    AirPlayV2.wait_for_playback_state_change = wait_for_playback_state_change  # type: ignore[attr-defined]
    AirPlayV2.supports_playback_state = supports_playback_state  # type: ignore[assignment]
    # silence unused in case of future use
    _ = original_setup_base


def _patch_airplay_player() -> None:
    async def play_url(self, url: str, position: float = 0) -> None:  # type: ignore[no-untyped-def]
        retry = 0
        async with airplay_player.timing_server(self.rtsp) as server:
            while retry < airplay_player.PLAY_RETRIES:
                _LOGGER.debug("Starting to play %s", url)
                resp = await self.stream_protocol.play_url(server.port, url, position)

                if resp.code == 500:
                    retry += 1
                    _LOGGER.debug(
                        "Failed to stream %s, retry %d of %d",
                        url,
                        retry,
                        airplay_player.PLAY_RETRIES,
                    )
                    await asyncio.sleep(1.0)
                    continue

                if 400 <= resp.code < 600:
                    raise exceptions.AuthenticationError(f"status code: {resp.code}")

                # Keep AirPlay session open until the task is cancelled (user stop).
                # Exiting this context tears down the stream on Apple TV — do NOT
                # treat flaky event/idle signals (common when Android sleeps) as end.
                await self._hold_playback_session()
                return
        raise exceptions.PlaybackError("Max retries exceeded")

    async def _hold_playback_session(self) -> None:  # type: ignore[no-untyped-def]
        """Stay connected so tvOS keeps playing; exit only on cancel."""
        # Brief grace period for "playing" event (best-effort, non-fatal).
        if getattr(self.stream_protocol, "supports_playback_state", False):
            deadline = asyncio.get_event_loop().time() + 20.0
            state = self.stream_protocol.playback_state
            while asyncio.get_event_loop().time() < deadline:
                if state == "playing":
                    break
                try:
                    state = await self.stream_protocol.wait_for_playback_state_change(
                        state, 2.0
                    )
                except Exception:
                    await asyncio.sleep(1.0)
        # Block forever until stop_playback cancels the task.
        try:
            while True:
                try:
                    if getattr(self.stream_protocol, "supports_playback_state", False):
                        await self.stream_protocol.wait_for_playback_state_change(
                            self.stream_protocol.playback_state, 30.0
                        )
                    else:
                        await asyncio.sleep(30.0)
                except asyncio.CancelledError:
                    raise
                except Exception:
                    # Transient Wi‑Fi / Doze glitches — keep holding the session.
                    await asyncio.sleep(2.0)
        except asyncio.CancelledError:
            raise

    async def _wait_for_media_to_end_from_events(self) -> None:  # type: ignore[no-untyped-def]
        # Legacy name kept for compatibility; now holds until cancel.
        await self._hold_playback_session()

    async def _wait_for_media_to_end(self) -> None:  # type: ignore[no-untyped-def]
        await self._hold_playback_session()

    AirPlayPlayer.play_url = play_url  # type: ignore[method-assign]
    AirPlayPlayer._hold_playback_session = _hold_playback_session  # type: ignore[attr-defined]
    AirPlayPlayer._wait_for_media_to_end_from_events = (  # type: ignore[attr-defined]
        _wait_for_media_to_end_from_events
    )
    AirPlayPlayer._wait_for_media_to_end = _wait_for_media_to_end  # type: ignore[method-assign]
