# RiskPilot-2026

RiskPilot is a Spring Boot trading monitor for NIFTY with live market-data integration, expiry tracking, health endpoints, and a lightweight frontend dashboard.

## What It Does

- Uses live NIFTY spot and option-chain data.
- Exposes REST and WebSocket endpoints for dashboard updates.
- Shows current spot, source, feed health, session state, trade history, and expiry date.
- Packages as a single Docker web service for Render deployment.

## Stack

- Java 17
- Spring Boot 4.0.5
- Spring Web, WebSocket, Security, Actuator, JPA
- H2 for local/dev runtime
- Docker for deployment
- Render Blueprint via root `render.yaml`

## Local Run

```bash
git clone https://github.com/VIGNESH6579/RiskPilot-2026.git
cd RiskPilot-2026/riskpilot
./mvnw clean test
./mvnw spring-boot:run
```

Open:

- `http://localhost:8080/`
- `http://localhost:8080/api/v1/health/state`

## Required Environment Variables

- `ANGEL_API_KEY`
- `ANGEL_CLIENT_ID`
- `ANGEL_PIN`
- `ANGEL_TOTP_SECRET`

Optional:

- `SPRING_PROFILES_ACTIVE=prod`
- `JAVA_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseStringDeduplication`
- `NIFTY_WEEKLY_EXPIRY_DAY`
- `NIFTY_EXPIRY_OVERRIDE`

## Important Endpoints

- `GET /`
- `GET /api/v1/data/health`
- `GET /api/v1/data/trade-history?limit=20`
- `GET /api/v1/monitor/state`
- `GET /api/v1/health/state`
- `GET /api/v1/health/state`
- `WS /ws`

## Deploying To Render

- The repo root contains `render.yaml`.
- The app builds from `riskpilot/Dockerfile`.
- Render should use the web-service health check path `/api/v1/health/state`.
- For a Git-linked Render service, each push to `main` can auto-deploy.

More deployment detail is in `PRODUCTION_SETUP.md`.
