# RiskPilot Production Deployment Checklist

**Target Environment:** Render Free Tier  
**Trading Symbol:** NIFTY  
**Start Date:** 2026-05-24  

---

## ✅ PRE-DEPLOYMENT (This Week)

### Configuration Verification
- [x] **TRADING_SYMBOL=NIFTY added to render.yaml** ✅ DONE
- [ ] **Verify MAX_SPOT_STALE_MS in MarketDataStateService.java**
  ```bash
  grep -A2 "private static final long MAX_SPOT_STALE_MS" \
    riskpilot/src/main/java/com/riskpilot/service/MarketDataStateService.java
  ```
  - Expected: `15_000L` (or at least `15000`)
  - If shows `5000`: **MUST CHANGE TO 15000** (Render GC safety)

- [ ] **Angel One VIX Token Test**
  - Go to: Render Dashboard → riskpilot-2026 → Environment
  - Find: `ANGEL_INDIA_VIX_TOKEN=999920005`
  - Verify it's set (not blank)
  - Monitor logs after deploy for `VIX_CIRCUIT_BREAKER` warnings

- [ ] **Database Strategy Decided**
  - [ ] PLAN A: Upgrade to paid PostgreSQL ($7/month)
  - [ ] PLAN B: Export/migrate before July 18
  - [ ] PLAN C: Accept data loss after July 18
  - [ ] **MUST SET REMINDER: July 10, 2026** (8 days before expiry)

- [ ] **Ntfy Webhook Test**
  ```bash
  curl -d "RiskPilot Test Signal" https://ntfy.sh/riskpilot-live-signals
  ```
  Then open: https://ntfy.sh/riskpilot-live-signals and verify message appears

---

## ⚠️ SECURITY AUDIT

- [ ] **Remove Hardcoded Service ID from CI**
  - File: `.github/workflows/deploy-render.yml` line 55
  - Current: `echo "  RENDER_SERVICE_ID = srv-d7ika967r5hc73bjihgg"`
  - Action: Delete this line (use GitHub secrets instead)

- [ ] **Verify All Secrets in Render Dashboard** (NOT in code)
  - [ ] ADMIN_USERNAME
  - [ ] ADMIN_PASSWORD
  - [ ] ANGEL_API_KEY
  - [ ] ANGEL_CLIENT_ID
  - [ ] ANGEL_PIN
  - [ ] ANGEL_TOTP_SECRET
  - [ ] ANGEL_CLIENT_PUBLIC_IP
  - [ ] OBSERVER_SECRET (random string, 32+ chars)
  - [ ] RISKPILOT_NTFY_AUTH_TOKEN (if using private ntfy)

- [ ] **Verify GitHub Actions Secrets**
  - [ ] RENDER_SERVICE_ID (never echo this)
  - [ ] RENDER_API_KEY
  - [ ] RENDER_SERVICE_URL (optional, for health check)

- [ ] **CORS Configuration Correct**
  - render.yaml line 31: `CORS_ALLOWED_ORIGINS = https://riskpilot-2026.onrender.com`
  - Should match your actual Render app URL

---

## 🚀 DEPLOYMENT

### Before Pushing to Main
- [ ] All issues in `ISSUE_ANALYSIS_AND_FIXES.md` reviewed
- [ ] MAX_SPOT_STALE_MS updated (if needed)
- [ ] Hardcoded service ID removed from deploy-render.yml
- [ ] render.yaml with TRADING_SYMBOL verified
- [ ] Local build successful: `mvn clean package -DskipTests`

### GitHub Push
```bash
git checkout -b prod-deployment
git add .
git commit -m "Production deployment: add TRADING_SYMBOL + security fixes"
git push origin prod-deployment
# Create pull request, review, then merge to main
```

### Monitor Render Build
- Go to: https://dashboard.render.com/services/riskpilot-2026
- Watch build log for errors
- Expected build time: 3-5 minutes
- Expected startup time: 2-3 minutes (cold start on free tier)

### Health Check
```bash
# Poll until healthy:
for i in {1..10}; do
  curl -s https://riskpilot-2026.onrender.com/actuator/health | jq .
  echo "Attempt $i: sleeping 30s..."
  sleep 30
done
```

---

## 📋 POST-DEPLOYMENT (First 2 Hours)

### Monitoring
- [ ] **Check Render Logs**
  - https://dashboard.render.com/services/riskpilot-2026 → Logs
  - Look for errors: `ERROR`, `Exception`, `FAIL`
  - Expected: `ApplicationReadyEvent`, `FeedHealthMonitor initialized`, `MarketDataStateService initialized`

- [ ] **Monitor for VIX Circuit Breaker**
  - Search logs for: `VIX_CIRCUIT_BREAKER`
  - If found: Angel One or Yahoo VIX fetch failed (expected occasionally)
  - If frequent (every 5 min): VIX token may be invalid

- [ ] **Check Session State**
  - GET: `https://riskpilot-2026.onrender.com/api/v1/engine/state`
  - Should return JSON with sessionActive, regime, feedStable, etc.
  - If error: Database connection issue

- [ ] **Verify Ntfy Notifications Sent**
  - Check: https://ntfy.sh/riskpilot-live-signals
  - Should see messages from the app (if trading)

---

## 🧪 PAPER MODE TESTING (First Day)

### Setup Paper Trading
1. Set environment in Render Dashboard: `PAPER_MODE=true`
2. Restart the app
3. Verify in logs: `⚠️ PAPER_MODE enabled via env var`

### Run Sample Trades
- [ ] **Morning Session (9:15-9:45 AM IST)**
  - Monitor opening range building
  - Verify OR high/low populated correctly

- [ ] **Signal Generation (10:00 AM - 1:00 PM IST)**
  - Trigger at least 2-3 trap signals
  - Check TrapEngine logic for consistency
  - Monitor P&L (TP1 hits, runner exits)

- [ ] **Verify All Gates**
  - Check logs for gate rejections (expected many)
  - Gates: regime, volatility, risk, time, feed health
  - At least 1 trade should be accepted

- [ ] **Log Analysis**
  - Export logs from Render dashboard
  - Search for: `Signal detected`, `ALLOW`, `REJECT`
  - Verify ATR, support/resistance calculations
  - Check daily reset at 9:14 AM

---

## 🔴 LIVE TRADING ACTIVATION

### Pre-Live Verification
- [ ] 2+ days of paper trading completed
- [ ] No crashes or persistent errors in logs
- [ ] At least 1 successful trade logged
- [ ] VIX signals working (not stuck in -1.0)
- [ ] Database growing (candles persisted)
- [ ] Ntfy notifications received

### Switch to Live Mode
1. Verify Angel One account funded (min ₹1,000)
2. Set environment: `PAPER_MODE=false`
3. Restart the app
4. Monitor logs for: `PAPER_MODE disabled`

### First Live Trade Monitoring
- [ ] Market open (9:15 AM IST)
- [ ] Monitor logs every 5 minutes
- [ ] First trade signal: capture full logs
- [ ] First trade execution: verify entry price
- [ ] Track TP1 hit and runner exit
- [ ] Verify trade persisted to database
- [ ] Check P&L calculation

---

## ⚠️ EMERGENCY PROCEDURES

### If App Crashes
```bash
1. Go to Render Dashboard → riskpilot-2026
2. Click "Manual Deploy"
3. Check logs for the error
4. If database issue:
   - Verify Render DB is still linked
   - Check DATABASE_URL in Environment
5. If Flyway migration error:
   - Check db migrations in riskpilot/src/main/resources/db/migration/
```

### If Zero Trades All Day
Check in order:
1. **VIX:** Look for `VIX_CIRCUIT_BREAKER` → test VIX token
2. **Feed:** Look for `FEED_UNSTABLE` or `DISCONNECTED` → Angel One API issue
3. **Gates:** Look for `REJECT` reasons → too strict filters
4. **Symbol:** Verify `TRADING_SYMBOL` in Render → should be NIFTY
5. **Regime:** Check regime calculation → if BLOCKED, manual intervention needed

### If High Losses (>3R negative)
- [ ] Enable PAPER_MODE immediately: `PAPER_MODE=true` + restart
- [ ] Review support/resistance calculations
- [ ] Check if market regime matches NIFTY volatility
- [ ] Review gate conditions (maybe too loose)

### Kill Switch Activation
If system behavior becomes erratic:
```bash
# Render Dashboard → Environment → Add temporary variable:
KILL_SWITCH=true

# This will:
# - Stop all new trade entries
# - Exit active trades at market
# - Log prominent error
```

---

## 📊 DAILY OPERATIONS

### Every Trading Day
- [ ] Check Render dashboard for app status (should be "Live")
- [ ] Monitor app logs during trading hours (9:15-15:30 IST)
- [ ] Count trades taken vs. trades attempted (check rejects)
- [ ] Verify P&L compound correctly across days

### Weekly Review
- [ ] Export trade history from database
- [ ] Calculate: Win rate, avg R per trade, largest loss
- [ ] Check VIX reliability (count circuit breaker events)
- [ ] Verify ntfy notifications received

### Monthly Review
- [ ] S/R lookback tuning (currently 20 candles = 100 min)
- [ ] Gate thresholds vs. actual signal quality
- [ ] Database size (candles, trades, option chains)
- [ ] Plan for July 18 database expiry (if not upgraded by now)

---

## 🎯 SUCCESS CRITERIA

✅ Deployment successful when:
1. App healthy for 1 hour (no crashes)
2. At least 1 trade signal generated in paper mode
3. Market data fresh (spot, VIX, candles updating)
4. Ntfy notifications working
5. Database recording trades & candles
6. All gates rejecting trades as expected (conservative is OK)

❌ Deployment FAILED if:
1. App crashes on startup
2. Database connection fails
3. Zero candles after 30 minutes (feed issue)
4. VIX stuck at -1.0 (both sources failed)
5. Logs show `ERROR` every few seconds

---

## CONTACTS & SUPPORT

**Render Dashboard:** https://dashboard.render.com  
**Angel One API Docs:** https://www.angelbroking.com/api  
**Ntfy.sh:** https://ntfy.sh/docs  
**GitHub Issues:** https://github.com/VIGNESH6579/RiskPilot-2026/issues  

---

**Last Updated:** 2026-05-24  
**Status:** Ready for deployment ✅
