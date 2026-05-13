import asyncio
import contextlib
import csv
import hashlib
import hmac
import json
import logging
import os
import time
from collections import OrderedDict

import aiohttp
from aiohttp import web

# ============================================================
# RiskPilot Observer — Production Hardened
#
# BEFORE problems:
# 1. Bound to 0.0.0.0:8765 — unreachable on Render (only $PORT exposed).
# 2. WebSocket had NO auth — any client could receive live trade data.
# 3. ntfy sent raw P&L, entry/exit prices, slippage to a public topic.
# 4. Hung ntfy call could block the entire async event loop.
#
# AFTER:
# 1. Binds 127.0.0.1:8766. Spring Boot proxies /observer/** externally.
# 2. Token auth: client must pass ?token= or Authorization: Bearer header.
# 3. ntfy content scrubbed — direction + exit reason only.
# 4. asyncio.timeout(5) wraps every ntfy call.
#
# ntfy Channel: https://ntfy.sh/riskpilot-live-signals
# UptimeRobot: https://uptimerobot.com (Monitor: /api/v1/monitor/state)
# ============================================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
)
logger = logging.getLogger("riskpilot.observer")

CSV_PATH            = os.environ.get("RISKPILOT_CSV_PATH", "shadow_live_forward_logs.csv")
NTFY_TOPIC          = os.environ.get("RISKPILOT_NTFY_TOPIC", "riskpilot-live-signals")
NTFY_AUTH_TOKEN     = os.environ.get("RISKPILOT_NTFY_AUTH_TOKEN", "")
ENABLE_NTFY         = os.environ.get("RISKPILOT_ENABLE_NTFY", "true").lower() == "true"
NOTIFY_COOLDOWN_SEC = float(os.environ.get("RISKPILOT_NOTIFY_COOLDOWN_SEC", "10"))
OBSERVER_SECRET     = os.environ.get("OBSERVER_SECRET", "")
PORT = int(os.environ.get("OBSERVER_PORT", "8766"))
HOST = os.environ.get("OBSERVER_HOST", "127.0.0.1")
MAX_CACHE_SIZE      = int(os.environ.get("RISKPILOT_MAX_CACHE_SIZE", "500"))

EXPECTED_COLUMNS = [
    "signalTime", "executionTime", "direction", "latencySec", "expectedEntry", "actualEntry",
    "entrySlippage", "expectedExit", "actualExit", "exitSlippage", "tp1Hit",
    "runnerCaptured", "mfe", "mae", "realizedR", "gateDecision", "rejectReason",
    "regime", "timePhase", "feedStable", "exitReason", "exitTime",
]

processed_signals: OrderedDict = OrderedDict()
active_websockets: set = set()
last_payload: dict | None = None
last_notify_ts: float = 0.0


def _ensure_csv_exists() -> None:
    if not os.path.exists(CSV_PATH):
        open(CSV_PATH, "a", encoding="utf-8").close()


def _to_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _authenticate(request: web.Request) -> bool:
    """Timing-safe token authentication."""
    if not OBSERVER_SECRET:
        logger.warning("OBSERVER_SECRET not set — auth DISABLED (dev mode only)")
        return True
    token = request.rel_url.query.get("token", "")
    if token and hmac.compare_digest(token.encode(), OBSERVER_SECRET.encode()):
        return True
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Bearer "):
        bearer = auth_header[7:]
        if hmac.compare_digest(bearer.encode(), OBSERVER_SECRET.encode()):
            return True
    return False


def parse_line(line: str):
    if not line:
        return None
    try:
        parts = next(csv.reader([line]))
    except Exception:
        return None
    first = parts[0].strip().lower()
    if first in ("signaltime", "version=2"):
        return None
    if len(parts) < len(EXPECTED_COLUMNS):
        return None
    signal_id = f"{parts[0]}_{parts[4]}"
    return {
        "id":             signal_id,
        "signalTime":     parts[0],
        "executeTime":    parts[1],
        "direction":      parts[2] if len(parts) > 2 else "UNKNOWN",
        "latencySec":     _to_float(parts[3]),
        "tp1Hit":         str(parts[10]).strip().lower() == "true",
        "runnerCaptured": str(parts[11]).strip().lower() == "true",
        "realizedR":      _to_float(parts[14]),
        "exitReason":     parts[20] if len(parts) > 20 else "",
        "observerTs":     time.time(),
        # Sensitive financial figures (actualEntry, slippage, mfe, mae) are
        # NOT included in the WebSocket broadcast — they stay in the DB and CSV only.
    }


async def tail_csv(file_path: str):
    _ensure_csv_exists()
    with open(file_path, "r", encoding="utf-8") as fh:
        fh.seek(0, os.SEEK_END)
        while True:
            line = fh.readline()
            if not line:
                await asyncio.sleep(0.15)
                continue
            if not line.endswith("\n"):
                await asyncio.sleep(0.05)
                continue
            yield parse_line(line.strip())


async def notify_ntfy(session: aiohttp.ClientSession, payload: dict):
    global last_notify_ts
    if not ENABLE_NTFY:
        return
    now = time.time()
    if now - last_notify_ts < NOTIFY_COOLDOWN_SEC:
        return

    # Scrubbed: direction + exit reason only — no financial figures
    direction   = payload.get("direction", "?")
    exit_reason = payload.get("exitReason", "?")
    tp1         = "✅ TP1" if payload.get("tp1Hit") else "❌ No TP1"
    runner      = "🏃 Runner" if payload.get("runnerCaptured") else ""
    message = f"RiskPilot | {direction} | {exit_reason} | {tp1} {runner}".strip()

    headers = {"Title": "RiskPilot Trade Alert", "Priority": "default", "Tags": "chart_with_upwards_trend"}
    if NTFY_AUTH_TOKEN:
        headers["Authorization"] = f"Bearer {NTFY_AUTH_TOKEN}"

    try:
        async with asyncio.timeout(5):
            async with session.post(
                f"https://ntfy.sh/{NTFY_TOPIC}",
                data=message.encode("utf-8"),
                headers=headers,
            ) as resp:
                if resp.status == 200:
                    last_notify_ts = now
                else:
                    logger.warning("ntfy HTTP %s", resp.status)
    except asyncio.TimeoutError:
        logger.warning("ntfy timed out — skipping")
    except Exception as exc:
        logger.error("ntfy error: %s", exc)


async def broadcast_payload(payload: dict):
    dead = []
    message = json.dumps(payload)
    for ws in list(active_websockets):
        try:
            await ws.send_str(message)
        except Exception:
            dead.append(ws)
    for ws in dead:
        active_websockets.discard(ws)


async def csv_reader_task(_app):
    global last_payload
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5)) as http_session:
        async for payload in tail_csv(CSV_PATH):
            if not payload or payload["id"] in processed_signals:
                continue
            processed_signals[payload["id"]] = True
            if len(processed_signals) > MAX_CACHE_SIZE:
                processed_signals.popitem(last=False)
            last_payload = payload
            await broadcast_payload(payload)
            await notify_ntfy(http_session, payload)


async def websocket_handler(request: web.Request):
    if not _authenticate(request):
        logger.warning("Rejected unauthenticated WS from %s", request.remote)
        raise web.HTTPUnauthorized(reason="Invalid or missing OBSERVER_SECRET token")

    ws = web.WebSocketResponse(heartbeat=20)
    await ws.prepare(request)
    active_websockets.add(ws)
    logger.info("WS connected: %s (total=%d)", request.remote, len(active_websockets))

    if last_payload:
        try:
            await ws.send_str(json.dumps(last_payload))
        except Exception:
            pass

    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT and msg.data.strip() == "ping":
                await ws.send_str('{"type":"pong"}')
            elif msg.type in (aiohttp.WSMsgType.ERROR, aiohttp.WSMsgType.CLOSE):
                break
    finally:
        active_websockets.discard(ws)
        logger.info("WS disconnected: %s (total=%d)", request.remote, len(active_websockets))

    return ws


async def health_handler(_request: web.Request):
    return web.json_response({
        "status": "ok",
        "csvPath": CSV_PATH,
        "connectedClients": len(active_websockets),
        "cacheSize": len(processed_signals),
        "hasLastPayload": bool(last_payload),
        "authEnabled": bool(OBSERVER_SECRET),
        "ntfyEnabled": ENABLE_NTFY,
    })


async def start_background_tasks(app):
    app["csv_listener"] = asyncio.create_task(csv_reader_task(app))


async def cleanup_background_tasks(app):
    app["csv_listener"].cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await app["csv_listener"]


app = web.Application()
app.router.add_get("/ws", websocket_handler)
app.router.add_get("/healthz", health_handler)
app.on_startup.append(start_background_tasks)
app.on_cleanup.append(cleanup_background_tasks)

if __name__ == "__main__":
    if not OBSERVER_SECRET:
        logger.warning("=" * 60)
        logger.warning("OBSERVER_SECRET not set — WebSocket auth DISABLED!")
        logger.warning("Set OBSERVER_SECRET in Render environment variables.")
        logger.warning("=" * 60)
    logger.info("Observer starting on %s:%d | csv=%s | auth=%s",
                HOST, PORT, CSV_PATH, "on" if OBSERVER_SECRET else "OFF")
    web.run_app(app, host=HOST, port=PORT, access_log=None)
