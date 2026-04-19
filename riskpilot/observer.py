import asyncio
import json
import os
from collections import OrderedDict
from aiohttp import web
import aiohttp

# Configuration
CSV_PATH = "shadow_live_forward_logs.csv"
NTFY_TOPIC = "riskpilot_shadow_alerts"
WS_PORT = 8765

# LRU Cache for Duplicate rejection
MAX_CACHE_SIZE = 100
processed_signals = OrderedDict()
active_websockets = set()

async def tail_csv(file_path):
    """Safely tails a file ignoring partial writes and restarts."""
    if not os.path.exists(file_path):
        open(file_path, 'a').close()
        
    with open(file_path, 'r') as f:
        f.seek(0, 2)
        while True:
            line = f.readline()
            if not line:
                await asyncio.sleep(0.1)
                continue
            
            if not line.endswith('\n'):
                continue
                
            yield parse_line(line.strip())

def parse_line(line):
    parts = line.split(',')
    if len(parts) < 11: return None
    signal_id = f"{parts[0]}_{parts[3]}" 
    return {
        "id": signal_id,
        "signalTime": parts[0],
        "executeTime": parts[1],
        "latencySec": parts[2],
        "expectedEntry": parts[3],
        "actualEntry": parts[4],
        "slippage": parts[5],
        "mfe": parts[6],
        "mae": parts[7],
        "isRunner": parts[8],
        "exitReason": parts[9],
        "exitTime": parts[10]
    }

async def notify_ntfy(session, payload):
    msg = f"Trade Exit: {payload['exitReason']}\nSlippage: {payload['slippage']}\nRunner: {payload['isRunner']}"
    try:
        async with session.post(f"https://ntfy.sh/{NTFY_TOPIC}", data=msg.encode('utf-8')) as response:
            pass
    except Exception as e:
        pass 

async def csv_reader_task(app):
    """Background task reading CSV and broadcasting to active WebSockets."""
    async with aiohttp.ClientSession() as http_session:
        async for payload in tail_csv(CSV_PATH):
            if not payload: continue
            
            if payload["id"] in processed_signals:
                continue
                
            processed_signals[payload["id"]] = True
            if len(processed_signals) > MAX_CACHE_SIZE:
                processed_signals.popitem(last=False)
                
            # Broadcast to all connected clients
            for ws in set(active_websockets):
                try:
                    await ws.send_str(json.dumps(payload))
                except Exception:
                    active_websockets.discard(ws)
            
            # Rate limited notification
            await notify_ntfy(http_session, payload)
            await asyncio.sleep(0.3) 

async def websocket_handler(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    active_websockets.add(ws)
    try:
        async for msg in ws:
            pass # We only send, we don't expect messages
    finally:
        active_websockets.discard(ws)
    return ws

async def health_handler(request):
    return web.Response(text="OK")

async def start_background_tasks(app):
    app['csv_listener'] = asyncio.create_task(csv_reader_task(app))

async def cleanup_background_tasks(app):
    app['csv_listener'].cancel()
    await app['csv_listener']

app = web.Application()
# Natively intercepts / (Render Health Check) and /ws (Frontend)
app.router.add_get('/', health_handler)
app.router.add_head('/', health_handler)
app.router.add_get('/ws', websocket_handler)

app.on_startup.append(start_background_tasks)
app.on_cleanup.append(cleanup_background_tasks)

if __name__ == "__main__":
    print(f"[*] Started RiskPilot Air-gapped Observer safely routing HTTP Health & WS on {WS_PORT}")
    web.run_app(app, host="0.0.0.0", port=WS_PORT, access_log=None)
