# RiskPilot Render Setup

This project is configured to run as a single Render web service using the root `render.yaml` Blueprint and `riskpilot/Dockerfile`.

## Render Service

- Service type: `web`
- Runtime: `docker`
- Plan: `free`
- Health check path: `/api/v1/health/state`
- Auto deploy: on commits to the linked branch

## Required Render Environment Variables

Add these in the Render dashboard:

```text
ANGEL_API_KEY=...
ANGEL_CLIENT_ID=...
ANGEL_PIN=...
ANGEL_TOTP_SECRET=...
SPRING_PROFILES_ACTIVE=prod
JAVA_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseStringDeduplication
```

Optional:

```text
NIFTY_WEEKLY_EXPIRY_DAY=TUESDAY
NIFTY_EXPIRY_OVERRIDE=
```

## Deploy Steps

1. Push the latest code to GitHub.
2. In Render, create or sync the service from the repository Blueprint.
3. Confirm the service points at the repo root `render.yaml`.
4. Save the required environment variables.
5. Deploy.

## Verify

After deploy, check:

```bash
curl https://your-service.onrender.com/api/v1/health/state
curl https://your-service.onrender.com/api/v1/data/health
curl https://your-service.onrender.com/api/v1/monitor/state
```

The frontend should be available at:

```text
https://your-service.onrender.com/
```
