# RiskPilot-2026 Production Deployment Guide

## 🚀 Complete Setup Instructions

### Prerequisites
- Render.com account
- Angel One trading account with API credentials
- GitHub repository access

---

## 📋 Step 1: Environment Variables Setup (Render Dashboard)

1. Go to: https://dashboard.render.com
2. Select your `riskpilot-2026` service
3. Click **Settings > Environment**
4. Add the following environment variables:

```
ANGEL_API_KEY=your_api_key_from_angel_one
ANGEL_CLIENT_ID=your_client_id
ANGEL_PIN=your_pin
ANGEL_TOTP_SECRET=your_totp_secret
ANGEL_CLIENT_PUBLIC_IP=your_broker_registered_public_ip
ANGEL_INDIA_VIX_TOKEN=your_angel_india_vix_token
NSE_TRADING_HOLIDAYS=2026-01-26,2026-02-26,2026-03-20,2026-03-31,2026-04-02,2026-04-03,2026-04-14,2026-05-01,2026-08-15,2026-10-02,2026-11-02,2026-11-03,2026-12-25
RISKPILOT_NTFY_TOPIC=riskpilot-live-signals
OBSERVER_PORT=8765
OBSERVER_HOST=127.0.0.1
JAVA_TOOL_OPTIONS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75
```

5. Click **Save** - Render will auto-redeploy

---

## 🔐 Step 2: Angel One Credentials Setup

### Get Your Angel One API Credentials:
1. Log in to Angel One account
2. Navigate to: **Settings > API**
3. Generate API Key (if not already generated)
4. Copy the following:
   - **API Key** → `ANGEL_API_KEY` 
   - **Client ID** → `ANGEL_CLIENT_ID` 
   - **PIN** → `ANGEL_PIN` 
   - **TOTP Secret** → `ANGEL_TOTP_SECRET` 

Also add the public IP registered with Angel One as `ANGEL_CLIENT_PUBLIC_IP`. Do not rely on runtime IP discovery in production.

Set `ANGEL_INDIA_VIX_TOKEN` to the Angel token for India VIX. If live VIX is unavailable during market hours, VIX-dependent strategy decisions are blocked instead of using a fallback value.

### In Render Dashboard:
- Paste each credential into corresponding environment variable
- These are **encrypted** and not visible in code

---

## 📡 Step 3: Health Monitoring Setup (UptimeRobot)

### Create UptimeRobot Monitor:
1. Go to: https://uptimerobot.com
2. Sign up or log in
3. Click **Add New Monitor**
4. Set:
   - **Monitor Type**: HTTP(s)
   - **URL**: `https://your-service.onrender.com/api/v1/health/pipeline`
   - **Interval**: 5 minutes
   - **Alert Contacts**: Email + Slack (optional)

5. Click **Create Monitor**

---

## 🔔 Step 4: ntfy.sh Alert Configuration

### Manual Subscription:
1. Go to: https://ntfy.sh/riskpilot-live-signals
2. Open in browser - alerts will appear in real-time
3. (Optional) Download ntfy app for mobile notifications

---

## 📊 Step 5: Verify Deployment

### Check Health Endpoint:
```bash
curl https://your-service.onrender.com/api/v1/health/pipeline
```

Expected response:
```json
{
  "status": "healthy",
  "tickFeed": { "streamActive": true },
  "optionChain": { "marketOpen": true, "live": true }
}
```

---

## ✅ Final Deployment Checklist
- [ ] Render service deployed successfully
- [ ] Health endpoint responds with status: healthy
- [ ] Angel One credentials authenticated
- [ ] WebSocket connection established
- [ ] UptimeRobot monitor created and active
- [ ] ntfy.sh topic subscribed
- [ ] Frontend dashboard accessible
