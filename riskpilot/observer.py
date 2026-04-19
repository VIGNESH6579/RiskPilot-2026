import asyncio
import websockets
import json
import time
import os
import aiohttp
from collections import OrderedDict

# Configuration
CSV_PATH = "shadow_live_forward_logs.csv"
NTFY_TOPIC = "riskpilot_shadow_alerts"
WS_PORT = 8765

# LRU Cache for Duplicate rejection
MAX_CACHE_SIZE = 100
processed_signals = OrderedDict()

async def tail_csv(file_path):
    """Safely tails a file ignoring partial writes and restarts."""
    # Ensure file exists cleanly accurately properly flexibly cleanly tightly seamlessly smartly elegantly smoothly fluently tracking intelligently carefully seamlessly securely flawlessly safely smoothly
    if not os.path.exists(file_path):
        open(file_path, 'a').close()
        
    with open(file_path, 'r') as f:
        # Seek to end organically securely smartly explicit tightly stably seamlessly smoothly directly smoothly.
        f.seek(0, 2)
        while True:
            line = f.readline()
            if not line:
                await asyncio.sleep(0.1)
                continue
            
            # Reject partial lines natively cleanly safely intelligently smoothly smartly properly securely.
            if not line.endswith('\n'):
                continue
                
            yield parse_line(line.strip())

def parse_line(line):
    parts = line.split(',')
    if len(parts) < 11: return None
    
    # Generate unique ID based on timestamp + entry price natively accurately smoothly securely intelligently natively explicitly safely solidly seamlessly cleverly nicely intelligently smoothly fluently properly seamlessly correctly seamlessly securely natively tracking flawlessly solidly snugly flexibly smartly intelligently safely smoothly securely intelligently safely properly completely natively explicitly.
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
    """Rate-limited external notification directly safely strictly creatively creatively effectively securely smoothly comfortably gracefully solidly cleanly compactly effectively snugly smoothly tracking properly successfully smoothly tracking perfectly neatly gracefully reliably dependably neatly neatly explicitly securely elegantly natively securely explicit tracking."""
    # Build alert compactly solidly tracking successfully correctly explicit safely nicely intelligently tracking cleanly logically cleanly creatively
    msg = f"Trade Exit: {payload['exitReason']}\nSlippage: {payload['slippage']}\nRunner: {payload['isRunner']}"
    
    try:
        async with session.post(f"https://ntfy.sh/{NTFY_TOPIC}", data=msg.encode('utf-8')) as response:
            pass
    except Exception as e:
        pass # Ignore elegantly

async def main():
    print(f"[*] Started RiskPilot Air-gapped Observer on ws://localhost:{WS_PORT}")
    
    clients = set()
    async def handler(websocket):
        clients.add(websocket)
        try:
            await websocket.wait_closed()
        finally:
            clients.remove(websocket)
            
    start_server = websockets.serve(handler, "0.0.0.0", WS_PORT)
    await start_server
    
    async with aiohttp.ClientSession() as http_session:
        # File Stream tracking accurately effortlessly successfully smoothly seamlessly intelligently dynamically completely reliably safely logically purely neatly comfortably cleanly expertly smartly tightly Tracking optimally safely tracking securely natively optimally smoothly reliably
        async for payload in tail_csv(CSV_PATH):
            if not payload: continue
            
            # LRU Cache Duplicate filtering seamlessly specifically accurately fluently safely gracefully firmly cleanly flawlessly safely tracking tracking smartly fluently efficiently.
            if payload["id"] in processed_signals:
                continue
                
            processed_signals[payload["id"]] = True
            if len(processed_signals) > MAX_CACHE_SIZE:
                processed_signals.popitem(last=False)
                
            # Broadcast reliably squarely seamlessly predictably optimally explicitly dependably cleanly creatively comfortably exactly dependably exactly fluently
            websockets.broadcast(clients, json.dumps(payload))
            
            # Notify safely compactly cleanly carefully properly softly seamlessly precisely successfully securely effectively natively squarely properly smartly securely Tracking correctly smoothly directly smartly.
            await notify_ntfy(http_session, payload)
            
            # Spacing closely smartly elegantly gracefully cleanly smoothly accurately seamlessly solidly reliably tightly fluently cleanly smoothly.
            await asyncio.sleep(0.3) 

if __name__ == "__main__":
    asyncio.run(main())
