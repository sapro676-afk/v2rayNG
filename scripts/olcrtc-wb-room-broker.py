#!/usr/bin/env python3
"""Create one WB Stream room, run olcRTC server on it, and publish client config."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from secrets import token_hex
from typing import Any


API_BASE = os.getenv("WBSTREAM_API_BASE", "https://stream.wb.ru").rstrip("/")
BIND = os.getenv("OLCRTC_BROKER_BIND", "127.0.0.1")
PORT = int(os.getenv("OLCRTC_BROKER_PORT", "18081"))
AUTH_TOKEN = os.getenv("OLCRTC_BROKER_TOKEN", "")

OLCRTC_BIN = os.getenv("OLCRTC_BIN", "./build/olcrtc-linux-amd64")
START_SERVER = os.getenv("OLCRTC_START_SERVER", "1") != "0"
CARRIER = os.getenv("OLCRTC_CARRIER", "wbstream")
TRANSPORT = os.getenv("OLCRTC_TRANSPORT", "vp8channel")
FIXED_ROOM_ID = os.getenv("OLCRTC_ROOM_ID", "")
CLIENT_ID = os.getenv("OLCRTC_CLIENT_ID", "abumba-video-android")
KEY = os.getenv("OLCRTC_KEY", token_hex(32))
LINK = os.getenv("OLCRTC_LINK", "direct")
DNS = os.getenv("OLCRTC_DNS", "1.1.1.1:53")
DATA_DIR = os.getenv("OLCRTC_DATA", "data")
DISPLAY_NAME = os.getenv("OLCRTC_WBSTREAM_DISPLAY_NAME", "Abumba olcRTC")
ROOM_TTL_SECONDS = int(os.getenv("OLCRTC_ROOM_TTL_SECONDS", "0"))
DEBUG = os.getenv("OLCRTC_DEBUG", "1") != "0"

CLIENT_SOCKS_HOST = os.getenv("OLCRTC_CLIENT_SOCKS_HOST", "127.0.0.1")
CLIENT_SOCKS_PORT = int(os.getenv("OLCRTC_CLIENT_SOCKS_PORT", "18080"))


class BrokerState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.room_id = FIXED_ROOM_ID
        self.created_at = time.time() if FIXED_ROOM_ID else 0.0
        self.process: subprocess.Popen[str] | None = None


state = BrokerState()
httpd: ThreadingHTTPServer | None = None


def request_json(method: str, path: str, payload: dict[str, Any] | None = None, token: str = "") -> dict[str, Any]:
    body = json.dumps(payload or {}).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(API_BASE + path, data=body, method=method)
    req.add_header("User-Agent", "olcrtc-room-broker")
    req.add_header("Accept", "application/json")
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")

    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        details = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"WB Stream API {method} {path} failed: HTTP {exc.code}: {details}") from exc

    return json.loads(data or "{}")


def create_wb_room() -> str:
    guest = request_json(
        "POST",
        "/auth/api/v1/auth/user/guest-register",
        {
            "displayName": DISPLAY_NAME,
            "device": {
                "deviceName": "Linux",
                "deviceType": "PARTICIPANT_DEVICE_TYPE_WEB_DESKTOP",
            },
        },
    )
    access_token = guest.get("accessToken")
    if not access_token:
        raise RuntimeError("WB Stream guest registration did not return accessToken")

    room = request_json(
        "POST",
        "/api-room/api/v2/room",
        {
            "roomType": "ROOM_TYPE_ALL_ON_SCREEN",
            "roomPrivacy": "ROOM_PRIVACY_FREE",
        },
        token=access_token,
    )
    room_id = room.get("roomId")
    if not room_id:
        raise RuntimeError("WB Stream create room did not return roomId")
    return str(room_id)


def stop_server_locked() -> None:
    process = state.process
    if process is None or process.poll() is not None:
        state.process = None
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    state.process = None


def start_server_locked(force: bool = False) -> None:
    ttl_expired = ROOM_TTL_SECONDS > 0 and state.created_at > 0 and time.time() - state.created_at > ROOM_TTL_SECONDS
    running = state.process is not None and state.process.poll() is None

    if FIXED_ROOM_ID:
        state.room_id = FIXED_ROOM_ID
        if running and not force:
            return
        if running:
            stop_server_locked()
        spawn_server_locked()
        return

    if state.room_id and not force and not ttl_expired:
        if running or not START_SERVER:
            return
        spawn_server_locked()
        return

    if running:
        stop_server_locked()

    state.room_id = create_wb_room()
    state.created_at = time.time()
    print(f"created WB Stream room: {state.room_id}", flush=True)

    spawn_server_locked()


def spawn_server_locked() -> None:
    if not START_SERVER:
        return
    cmd = [
        OLCRTC_BIN,
        "-mode",
        "srv",
        "-carrier",
        CARRIER,
        "-transport",
        TRANSPORT,
        "-id",
        state.room_id,
        "-client-id",
        CLIENT_ID,
        "-key",
        KEY,
        "-link",
        LINK,
        "-dns",
        DNS,
        "-data",
        DATA_DIR,
    ]
    if DEBUG:
        cmd.append("--debug")
    state.process = subprocess.Popen(cmd, text=True)
    print(f"started olcRTC server pid={state.process.pid}", flush=True)


def current_config() -> dict[str, Any]:
    with state.lock:
        start_server_locked()
        return {
            "olcrtc": {
                "provider": CARRIER,
                "transport": TRANSPORT,
                "room_id": state.room_id,
                "client_id": CLIENT_ID,
                "key": KEY,
                "link": LINK,
                "socks_host": CLIENT_SOCKS_HOST,
                "socks_port": CLIENT_SOCKS_PORT,
            },
            "lease": {
                "created_at_unix": int(state.created_at),
                "room_ttl_seconds": ROOM_TTL_SECONDS,
                "managed_room": not bool(FIXED_ROOM_ID),
            },
        }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path.split("?", 1)[0] == "/healthz":
            self.write_json({"ok": True})
            return
        if self.path.split("?", 1)[0] != "/config.json":
            self.send_error(404)
            return
        if not self.authorized():
            return
        try:
            self.write_json(current_config())
        except Exception as exc:
            self.send_error(503, str(exc))

    def do_POST(self) -> None:
        if self.path.split("?", 1)[0] != "/refresh":
            self.send_error(404)
            return
        if not self.authorized():
            return
        try:
            with state.lock:
                start_server_locked(force=True)
            self.write_json(current_config())
        except Exception as exc:
            self.send_error(503, str(exc))

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"{self.address_string()} - {fmt % args}", flush=True)

    def authorized(self) -> bool:
        if not AUTH_TOKEN:
            return True
        if self.headers.get("Authorization") == f"Bearer {AUTH_TOKEN}":
            return True
        self.send_error(401)
        return False

    def write_json(self, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def shutdown(_signum: int, _frame: Any) -> None:
    with state.lock:
        stop_server_locked()
    if httpd is not None:
        httpd.shutdown()


def main() -> None:
    global httpd
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    with state.lock:
        start_server_locked()
    if FIXED_ROOM_ID:
        print(f"using fixed WB Stream room: {FIXED_ROOM_ID}", flush=True)
    httpd = ThreadingHTTPServer((BIND, PORT), Handler)
    print(f"broker listening on http://{BIND}:{PORT}/config.json", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
