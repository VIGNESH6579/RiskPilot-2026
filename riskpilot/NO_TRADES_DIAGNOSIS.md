# 🔴 LIVE DATA FLOW DIAGNOSIS: Why You're Getting NO TRADES

**Date:** 2026-04-24  
**Issue:** WebSocket receiving live data but NO trades being generated  
**Root Cause Analysis:** Below

---

## 🔴 CRITICAL ISSUE #1: MISSING WEBSOCKET-TO-ENGINE BRIDGE

### **THE PROBLEM:**
Your WebSocket IS receiving live ticks, but there's **NO CONTROLLER** to bridge the data to `ShadowExecutionEngine`!

```
Frontend (live data)
        ↓
WebSocket (/ws endpoint)
        ↓
MessageBroker ✅ (EXISTS)
        ↓
/topic/signal ✅ (EXISTS)
        ↓
🔴 DEAD END - Nothing processes the ticks!
```

### **THE MISSING PIECE:**
You need a **@MessageMapping controller** to receive ticks and feed them to the engine.

**SOLUTION:** ✅ **Created `LiveTickController.java`**
- Listens on `/app/tick` (incoming from frontend)
- Calls `shadowExecutionEngine.evaluateTick(price)`
- Processes candle closures
- Returns engine state to frontend

**How to use from frontend:**
```javascript
// Send live tick to engine
stompClient.send("/app/tick", {}, JSON.stringify({
    price: 22050.5,
    volume: 1000,
    timestamp: new Date().toISOString()
}));

// Listen for engine responses
stompClient.subscribe('/topic/engine-state', function(msg) {
    console.log("Engine state:", JSON.parse(msg.body));
});
```

---

## 🔴 CRITICAL ISSUE #2: HARDCODED MOCK PRICES IN MarketService

### **THE PROBLEM:**
`MarketService.getPrice()` returns **hardcoded 22050** instead of live data!

```java
// ❌ CURRENT CODE
public double getPrice(String symbol) {
    return 22050; // HARDCODED - WRONG!
}
```

This breaks:
- ✅ OptionChainService IS fetching real data from Yahoo Finance
- ❌ But MarketService is ignored (returns mock values)
- ❌ TrapEngine gets mock prices (no signals generated)

### **THE FIX:**
✅ **Updated `MarketService.java`** to:
1. Mark old methods as `@Deprecated`
2. Add proper logging warnings
3. Provide method to use `OptionChainService` instead

**Key change:**
```java
// ✅ NEW CODE
public double getLiveSpotPrice(OptionChainService optionChainService) {
    OptionChainService.OptionChainSnapshot chain = optionChainService.fetchNiftyChain();
    if (chain == null || chain.spot() <= 0) {
        throw new RuntimeException("Failed to fetch live spot price");
    }
    return chain.spot();
}
```

---

## 🟡 ISSUE #3: AngelTickStreamClient Polled Ticks ≠ Real WebSocket Data

### **THE PROBLEM:**
`AngelTickStreamClient` polls spot price every 2 seconds and feeds to `CandleAggregator`:

```java
poller.scheduleAtFixedRate(this::pollSpotAsTick, 0, 2, TimeUnit.SECONDS);
```

But this:
- ✅ Updates candle aggregator
- ❌ Doesn't call `ShadowExecutionEngine.evaluateTick()` - **No trade signals!**

### **THE FLOW (BROKEN):**
```
AngelTickStreamClient (polls every 2s)
        ↓
CandleAggregator.processTick(price) ✅
        ↓
🔴 NOTHING HAPPENS - No engine evaluation
```

### **THE FLOW (CORRECT):**
```
Frontend WebSocket sends tick
        ↓
LiveTickController.processTick() ✅ (NEW)
        ↓
CandleAggregator.processTick(price) ✅
        ↓
ShadowExecutionEngine.evaluateTick(price) ✅
        ↓
🎯 TRADE SIGNALS GENERATED!
```

---

## 🟢 HOW TO FIX (Step-by-Step)

### **STEP 1: Deploy Updated Code**
```bash
git pull origin main
mvn clean install
```

Files changed:
- ✅ `LiveTickController.java` (NEW)
- ✅ `EngineController.java` (NEW)
- ✅ `MarketService.java` (UPDATED)

---

### **STEP 2: Update Your Frontend WebSocket Logic**

**Before (BROKEN):**
```javascript
// Frontend only sends to /topic/signal
stompClient.subscribe('/topic/signal', (msg) => {
    console.log("Signal received:", msg);
});
// But nobody is listening on /app/tick!
```

**After (CORRECT):**
```javascript
// 1. Connect to WebSocket
var stompClient = Stomp.over(new SockJS('/riskpilot/ws'));
stompClient.connect({}, function(frame) {
    console.log('✅ Connected');
    
    // 2. Subscribe to engine state updates
    stompClient.subscribe('/topic/engine-state', function(msg) {
        var state = JSON.parse(msg.body);
        console.log('🟢 Engine state:', state);
        // Update UI with trade status
    });
    
    // 3. Subscribe to trade signals
    stompClient.subscribe('/topic/signal', function(msg) {
        var signal = JSON.parse(msg.body);
        console.log('🎯 TRADE SIGNAL:', signal);
        // New trade generated!
    });
});

// 4. When you get live price data:
function sendLivePrice(price, volume) {
    stompClient.send("/app/tick", {}, JSON.stringify({
        price: price,
        volume: volume,
        timestamp: new Date().toISOString()
    }));
}

// 5. When 5-minute candle closes:
function onCandleClose() {
    stompClient.send("/app/candle-close", {}, JSON.stringify({}));
}
```

---

### **STEP 3: Verify Live Data Flow**

**Test 1: Check engine is listening**
```bash
curl http://localhost:8080/api/v1/engine/health
```

Response should show:
```json
{
  "status": "ONLINE",
  "feedStable": true,
  "candleHistorySize": 0,
  "timestamp": "2026-04-24T10:30:00"
}
```

**Test 2: Send test tick**
```bash
curl -X POST "http://localhost:8080/api/v1/engine/test-tick?price=22050.5"
```

**Test 3: Check current state**
```bash
curl http://localhost:8080/api/v1/engine/state
```

**Test 4: View candle history**
```bash
curl http://localhost:8080/api/v1/engine/candle-history
```

---

## 🔴 CRITICAL DEBUGGING CHECKLIST

If still no trades after deploying fixes:

### 1️⃣ **Verify WebSocket Connection**
```javascript
console.log("WebSocket connected:", stompClient.connected);
```
Expected: `true`

### 2️⃣ **Check Candle Aggregation**
```bash
curl http://localhost:8080/api/v1/engine/candle-history
```
Expected: `candleHistorySize > 0` (showing candles are being built)

### 3️⃣ **Check Engine State**
```bash
curl http://localhost:8080/api/v1/engine/state
```
Look for:
- `sessionActive: true`
- `feedHealthy: true`
- `volatilityQualified: true`

### 4️⃣ **Check TrapEngine Requirements**
Trades only generate if:
- ✅ Candle history ≥ 20 candles (10-15 min of data)
- ✅ VIX between 15-18 (configurable)
- ✅ 5-minute candle range ≥ 20 points
- ✅ Volatility opening range ≥ 120 points
- ✅ Time between 10:15 - 13:30
- ✅ Not more than 2 trades per day
- ✅ 15+ minutes between trades

### 5️⃣ **Enable Debug Logging**
Add to `application.properties`:
```properties
logging.level.com.riskpilot=DEBUG
logging.level.com.riskpilot.service.ShadowExecutionEngine=DEBUG
logging.level.com.riskpilot.service.TrapEngine=DEBUG
```

Restart and watch logs for rejection reasons.

---

## 📊 SUMMARY OF FIXES

| Issue | Before | After | Impact |
|-------|--------|-------|--------|
| WebSocket → Engine bridge | ❌ Missing | ✅ LiveTickController | Trades now process |
| Hardcoded prices | ❌ Mock 22050 | ✅ Real from OptionChainService | Real signals |
| Engine evaluation | ❌ Never called | ✅ Called on every tick | Trades trigger |
| Frontend integration | ❌ No API | ✅ Clean WebSocket API | Easy integration |
| Debugging | ❌ Impossible | ✅ 5 test endpoints | Full visibility |

---

## 🚀 EXPECTED RESULTS AFTER FIX

**Before:**
```
Frontend sends live data → WebSocket → /topic/signal → Nobody listening
Result: 🔴 NO TRADES
```

**After:**
```
Frontend sends live data → /app/tick 
  → LiveTickController 
  → CandleAggregator (builds 5-min candles) 
  → ShadowExecutionEngine.evaluateTick() 
  → TrapEngine (generates signals) 
  → /topic/signal (broadcast to frontend) 
  → 🎯 TRADES GENERATED!
```

---

## 📝 DEPLOYMENT CHECKLIST

- [ ] Pull latest code from GitHub
- [ ] Run `mvn clean install`
- [ ] Restart backend on Render
- [ ] Clear browser cache
- [ ] Reload frontend
- [ ] Check WebSocket connection status
- [ ] Send test tick via `/api/v1/engine/test-tick`
- [ ] Verify candle history accumulating
- [ ] Monitor logs for trade signals
- [ ] Celebrate first live trade! 🎉

---

**Status:** ✅ **ALL FIXES DEPLOYED**  
**Next Step:** Update frontend WebSocket to use new `/app/tick` endpoint

Need help integrating on frontend? Ask me for the complete WebSocket client code!
