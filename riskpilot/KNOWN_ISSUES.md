# RiskPilot-2026: Known Issues & Operational Notes

## ⚠️ CRITICAL OPERATIONAL NOTES

### 1. Kill-Switch Auto-Exit
The system is configured to **automatically exit** 10 seconds after a kill-switch is triggered (`RISKPILOT_EXIT_ON_KILL_SWITCH=true`). 
- **Reason:** In a containerized environment (like Render), an exit triggers a restart, which allows the system to attempt a clean recovery.
- **Action:** If the kill-switch triggers repeatedly, check the logs for the root cause (e.g., persistent feed instability or critical slippage).

### 2. Early Session Confidence
Technical confidence scores (RegimeConfidenceEngine) may be low in the first 20 minutes of the session (9:15 - 9:35 AM).
- **Behavior:** An early-session bypass allows trading before 9:50 AM if the Opening Range is valid, even if technical scores are below 55.
- **Note:** Use median scores for technical components when candle data is sparse (< 5 candles).

### 3. VIX Data Fallback
If the Angel One VIX feed is unavailable, the system fallbacks to:
1. Yahoo Finance (via `VixService`)
2. `RISK_VIX_FALLBACK` (default: 15.0)
- **Check:** Monitor logs for "VIX fetched from Yahoo Finance" or "using RISK_VIX_FALLBACK".

---

## 🐞 KNOWN BUGS / LIMITATIONS

### 1. Feed Instability during Volatility
During extreme market volatility, the Angel One WebSocket may experience "stale data" warnings.
- **Status:** Mitigation in place (Watchdog in `AngelTickStreamClient`).
- **Symptom:** Logs show "⚠️ Feed stale (45000ms)".

### 2. Memory Constraints
The JVM is tuned for 512MB containers.
- **Limit:** Avoid increasing `MAX_TRADES_PER_DAY` beyond 10 to prevent heap exhaustion from large candle histories.

### 3. Public IP Discovery
If `ANGEL_CLIENT_PUBLIC_IP` is not set, the system attempts to discover it via `api.ipify.org`.
- **Issue:** If the discovery service is down, it defaults to local IP, which may cause Angel One auth rejection.
- **Fix:** Always set `ANGEL_CLIENT_PUBLIC_IP` in production.
