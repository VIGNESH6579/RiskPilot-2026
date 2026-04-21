import asyncio
import csv
import json
import os
import time
from collections import OrderedDict

import aiohttp
from aiohttp import web

CSV_PATH = os.environ.get("RISKPILOT_CSV_PATH", "shadow_live_forward_logs.csv")
NTFY_TOPIC = os.environ.get("RISKPILOT_NTFY_TOPIC", "riskpilot_shadow_alerts")
ENABLE_NTFY = os.environ.get("RISKPILOT_ENABLE_NTFY", "true").lower() == "true"
NOTIFY_COOLDOWN_SEC = float(os.environ.get("RISKPILOT_NOTIFY_COOLDOWN_SEC", "10"))
PORT = int(os.environ.get("PORT", "8080"))
MAX_CACHE_SIZE = int(os.environ.get("RISKPILOT_MAX_CACHE_SIZE", "500"))

processed_signals = OrderedDict()
active_websockets = set()
last_payload = None
last_notify_ts = 0.0


def _ensure_csv_exists() -> None:
    if not os.path.exists(CSV_PATH):
        open(CSV_PATH, "a", encoding="utf-8").close()


def _to_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def parse_line(line: str):
    if not line:
        return None
    try:
        parts = next(csv.reader([line]))
    except Exception:
        return None
    if len(parts) < 11:
        return None

    if parts[0].strip().lower() == "signaltime":
        return None

    signal_id = f"{parts[0]}_{parts[3]}"
    return {
        "id": signal_id,
        "signalTime": parts[0],
        "executeTime": parts[1],
        "latencySec": _to_float(parts[2]),
        "expectedEntry": _to_float(parts[3]),
        "actualEntry": _to_float(parts[4]),
        "slippage": _to_float(parts[5]),
        "mfe": _to_float(parts[6]),
        "mae": _to_float(parts[7]),
        "isRunner": str(parts[8]).strip().lower() == "true",
        "exitReason": parts[9],
        "exitTime": parts[10],
        "observerTs": time.time(),
    }


async def tail_csv(file_path: str):
    _ensure_csv_exists()
    with open(file_path, "r", encoding="utf-8") as file_handle:
        file_handle.seek(0, os.SEEK_END)
        while True:
            line = file_handle.readline()
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

    message = (
        f"RiskPilot Shadow Exit\n"
        f"Reason: {payload.get('exitReason')}\n"
        f"Slippage: {payload.get('slippage')}\n"
        f"Runner: {payload.get('isRunner')}\n"
        f"Latency(s): {payload.get('latencySec')}"
    )
    headers = {
        "Title": "RiskPilot Shadow Alert",
        "Priority": "default",
        "Tags": "chart_with_upwards_trend,warning",
    }
    try:
        async with session.post(
            f"https://ntfy.sh/{NTFY_TOPIC}", data=message.encode("utf-8"), headers=headers
        ):
            last_notify_ts = now
    except Exception:
        pass


async def broadcast_payload(payload: dict):
    dead_sockets = []
    message = json.dumps(payload)
    for ws in active_websockets:
        try:
            await ws.send_str(message)
        except Exception:
            dead_sockets.append(ws)
    for ws in dead_sockets:
        active_websockets.discard(ws)


async def csv_reader_task(_app):
    global last_payload
    timeout = aiohttp.ClientTimeout(total=5)
    async with aiohttp.ClientSession(timeout=timeout) as http_session:
        async for payload in tail_csv(CSV_PATH):
            if not payload:
                continue
            if payload["id"] in processed_signals:
                continue

            processed_signals[payload["id"]] = True
            if len(processed_signals) > MAX_CACHE_SIZE:
                processed_signals.popitem(last=False)

            last_payload = payload
            await broadcast_payload(payload)
            await notify_ntfy(http_session, payload)


async def websocket_handler(request):
    ws = web.WebSocketResponse(heartbeat=20)
    await ws.prepare(request)
    active_websockets.add(ws)

    if last_payload:
        await ws.send_str(json.dumps(last_payload))

    try:
        async for _ in ws:
            continue
    finally:
        active_websockets.discard(ws)
    return ws


async def health_handler(_request):
    return web.json_response(
        {
            "status": "ok",
            "csvPath": CSV_PATH,
            "connectedClients": len(active_websockets),
            "cacheSize": len(processed_signals),
            "hasLastPayload": bool(last_payload),
        }
    )


async def frontend_handler(_request):
    return web.FileResponse("frontend.html")


async def start_background_tasks(app):
    app["csv_listener"] = asyncio.create_task(csv_reader_task(app))


async def cleanup_background_tasks(app):
    app["csv_listener"].cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await app["csv_listener"]


import contextlib

app = web.Application()
app.router.add_get("/", frontend_handler)
app.router.add_get("/ws", websocket_handler)
app.router.add_get("/healthz", health_handler)
app.on_startup.append(start_background_tasks)
app.on_cleanup.append(cleanup_background_tasks)

if __name__ == "__main__":
    print(f"[*] RiskPilot observer listening on :{PORT}, csv={CSV_PATH}")
    web.run_app(app, host="0.0.0.0", port=PORT, access_log=None)
