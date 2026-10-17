"""
Brücke zu Music Assistant (MA): Player auflisten, Warteschlange abspielen, steuern.

MA spricht JSON über eine WebSocket-Verbindung (`/ws`). Der Token kommt bei jeder Anfrage von der App
und wird im Helfer nicht gespeichert. Musik wird als Navidrome-Stream-URL übergeben; MA spielt sie
auf Echo, Google Cast, DLNA, AirPlay usw. ab.
"""
from __future__ import annotations

import asyncio
import json
import urllib.parse
import uuid
from typing import Any

import websockets


class MaError(RuntimeError):
    pass


def _ws_url(base: str) -> str:
    base = base.strip().rstrip("/")
    if base.startswith("https://"):
        return "wss://" + base[len("https://"):] + "/ws"
    if base.startswith("http://"):
        return "ws://" + base[len("http://"):] + "/ws"
    return "ws://" + base + "/ws"


async def _call_async(base: str, token: str, commands: list[tuple[str, dict]]) -> list[Any]:
    try:
        async with websockets.connect(_ws_url(base), open_timeout=8, max_size=8 * 1024 * 1024) as ws:
            await asyncio.wait_for(ws.recv(), 8)  # Server-Info
            results = []
            for command, args in [("auth", {"token": token})] + commands:
                mid = uuid.uuid4().hex
                await ws.send(json.dumps({"message_id": mid, "command": command, "args": args}))
                while True:
                    msg = json.loads(await asyncio.wait_for(ws.recv(), 20))
                    if msg.get("message_id") != mid:
                        continue  # Ereignisse anderer Art überspringen
                    if msg.get("error_code") is not None:
                        details = msg.get("details") or "Fehler"
                        if command == "auth":
                            raise MaError("Music Assistant hat den Token abgelehnt")
                        raise MaError(f"Music Assistant ({command}): {details}")
                    results.append(msg.get("result"))
                    break
            return results[1:]
    except MaError:
        raise
    except (OSError, asyncio.TimeoutError, websockets.WebSocketException) as e:
        raise MaError(f"Music Assistant nicht erreichbar: {e}") from e


def call(base: str, token: str, *commands: tuple[str, dict]) -> list[Any]:
    return asyncio.run(_call_async(base, token, list(commands)))


def _player(p: dict) -> dict:
    media = p.get("current_media") or {}
    return {
        "id": p.get("player_id"),
        "name": p.get("display_name") or p.get("name") or p.get("player_id"),
        "provider": p.get("provider"),
        "type": p.get("type"),
        "available": bool(p.get("available", True)),
        "powered": p.get("powered"),
        "state": p.get("playback_state") or p.get("state"),
        "volume": p.get("volume_level"),
        "title": media.get("title"),
        "artist": media.get("artist"),
        "image": media.get("image_url"),
        "duration": media.get("duration"),
    }


def players(base: str, token: str) -> list[dict]:
    (result,) = call(base, token, ("players/all", {}))
    out = [_player(p) for p in (result or [])]
    # Gruppen-/Sync-Player und ausgeblendete Einträge nicht anbieten
    return [p for p in out if p["id"] and p["available"] and p.get("type") != "group"]


def stream_url(nav: dict, song_id: str) -> str:
    query = urllib.parse.urlencode({
        "u": nav["user"], "t": nav["token"], "s": nav["salt"], "v": "1.16.1", "c": "NaviDroid-MA", "id": song_id,
    })
    return f"{nav['url'].rstrip('/')}/rest/stream?{query}"


def play(base: str, token: str, player_id: str, nav: dict, song_ids: list[str]) -> None:
    if not song_ids:
        raise MaError("Keine Titel")
    media = [stream_url(nav, sid) for sid in song_ids[:500]]
    call(base, token, ("player_queues/play_media", {"queue_id": player_id, "media": media, "option": "replace"}))


COMMANDS = {
    "play": ("players/cmd/play", lambda pid, v: {"player_id": pid}),
    "pause": ("players/cmd/pause", lambda pid, v: {"player_id": pid}),
    "play_pause": ("players/cmd/play_pause", lambda pid, v: {"player_id": pid}),
    "stop": ("players/cmd/stop", lambda pid, v: {"player_id": pid}),
    "next": ("player_queues/next", lambda pid, v: {"queue_id": pid}),
    "previous": ("player_queues/previous", lambda pid, v: {"queue_id": pid}),
    "seek": ("player_queues/seek", lambda pid, v: {"queue_id": pid, "position": int(v or 0)}),
    "volume": ("players/cmd/volume_set", lambda pid, v: {"player_id": pid, "volume_level": int(v or 0)}),
}


def command(base: str, token: str, player_id: str, name: str, value: float | None = None) -> None:
    if name not in COMMANDS:
        raise MaError(f"Unbekannter Befehl {name}")
    cmd, args = COMMANDS[name]
    call(base, token, (cmd, args(player_id, value)))


def state(base: str, token: str, player_id: str) -> dict:
    player, queue = call(base, token, ("players/get", {"player_id": player_id}), ("player_queues/get", {"queue_id": player_id}))
    p = _player(player or {})
    q = queue or {}
    item = q.get("current_item") or {}
    p["elapsed"] = q.get("elapsed_time") or 0
    p["duration"] = p.get("duration") or item.get("duration")
    p["title"] = p.get("title") or item.get("name")
    p["queueIndex"] = q.get("current_index")
    return p
