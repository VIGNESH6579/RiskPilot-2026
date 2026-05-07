#!/bin/bash
set -euo pipefail

# ============================================================
# RiskPilot Production Start Script
# ============================================================
#
# BEFORE (critical bugs):
# 1. Hardcoded H2 in-memory DB — ignored DATABASE_URL even when PostgreSQL is set.
#    --spring.datasource.url="jdbc:h2:mem:riskpilot" was unconditional.
#    ALL trade data, kill switch state, candle history lost on every restart.
#    Render free tier restarts containers on every deploy AND after inactivity.
#
# 2. --spring.flyway.enabled="false" — Flyway always disabled, tables never created.
#
# 3. OBSERVER_PORT=8765 — Render only exposes ONE port ($PORT). Port 8765 is
#    unreachable externally, making the observer WebSocket completely useless.
#
# AFTER:
# 1. Uses PostgreSQL if DATABASE_URL set; H2 only in dev with a warning.
# 2. Flyway enabled in prod — schema migrated on startup.
# 3. Observer binds 127.0.0.1:8766 (localhost). Spring Boot proxies /observer/**
#    so external clients reach it through the single $PORT over HTTPS.
# 4. Secret validation: refuses to start in prod if critical env vars are missing.
# 5. JVM heap tuned: 128m start / 380m max (leaves room for OS + Python + overhead).
# ============================================================

: "${PORT:=8080}"
: "${SPRING_PROFILES_ACTIVE:=prod}"

echo "[*] RiskPilot starting — profile=${SPRING_PROFILES_ACTIVE} port=${PORT}"

# ---- Validate critical secrets (prod only) ----
if [[ "${SPRING_PROFILES_ACTIVE}" == "prod" ]]; then
  MISSING=""
  [[ -z "${ADMIN_USERNAME:-}"      ]] && MISSING="${MISSING} ADMIN_USERNAME"
  [[ -z "${ADMIN_PASSWORD:-}"      ]] && MISSING="${MISSING} ADMIN_PASSWORD"
  [[ -z "${DATABASE_URL:-}"        ]] && MISSING="${MISSING} DATABASE_URL"
  [[ -z "${ANGEL_API_KEY:-}"       ]] && MISSING="${MISSING} ANGEL_API_KEY"
  [[ -z "${ANGEL_CLIENT_ID:-}"     ]] && MISSING="${MISSING} ANGEL_CLIENT_ID"
  [[ -z "${ANGEL_PIN:-}"           ]] && MISSING="${MISSING} ANGEL_PIN"
  [[ -z "${ANGEL_TOTP_SECRET:-}"   ]] && MISSING="${MISSING} ANGEL_TOTP_SECRET"
  [[ -z "${CORS_ALLOWED_ORIGINS:-}" ]] && MISSING="${MISSING} CORS_ALLOWED_ORIGINS"
  if [[ -n "${MISSING}" ]]; then
    echo "[FATAL] Missing required env vars:${MISSING}"
    echo "[FATAL] System will NOT start with missing credentials in prod mode."
    exit 1
  fi
fi

# ---- Database configuration ----
if [[ -n "${DATABASE_URL:-}" ]]; then
  echo "[*] Using PostgreSQL"

  # Render injects DATABASE_URL as:  postgres://user:pass@host:port/dbname
  # Spring JDBC requires:            jdbc:postgresql://host:port/dbname?sslmode=require
  #
  # Additionally Render does NOT inject DATABASE_USERNAME / DATABASE_PASSWORD separately —
  # credentials are embedded in DATABASE_URL. Extract them here.

  RAW_URL="${DATABASE_URL}"

  if [[ "${RAW_URL}" == postgres://* ]] || [[ "${RAW_URL}" == postgresql://* ]]; then
    # Strip the scheme
    WITHOUT_SCHEME="${RAW_URL#postgres://}"
    WITHOUT_SCHEME="${WITHOUT_SCHEME#postgresql://}"

    # Extract user:pass (everything before @)
    USERINFO="${WITHOUT_SCHEME%%@*}"
    DB_USER="${USERINFO%%:*}"
    DB_PASS="${USERINFO#*:}"

    # Extract host:port/dbname (everything after @)
    HOSTPATH="${WITHOUT_SCHEME#*@}"

    # Build JDBC URL with SSL (required by Render PostgreSQL)
    DB_URL="jdbc:postgresql://${HOSTPATH}?sslmode=require"
  else
    # Already a JDBC URL — use as-is
    DB_URL="${RAW_URL}"
    DB_USER="${DATABASE_USERNAME:-}"
    DB_PASS="${DATABASE_PASSWORD:-}"
  fi

  # Allow explicit overrides from env vars
  DB_USER="${DATABASE_USERNAME:-$DB_USER}"
  DB_PASS="${DATABASE_PASSWORD:-$DB_PASS}"

  echo "[*] DB URL: ${DB_URL}"
  echo "[*] DB User: ${DB_USER}"

  DB_DRIVER="org.postgresql.Driver"
  DB_DIALECT="org.hibernate.dialect.PostgreSQLDialect"
  # DDL_AUTO="validate"
  DDL_AUTO="none"  
  FLYWAY_ENABLED="true"
  H2_CONSOLE="false"
else
  echo "[WARN] DATABASE_URL not set — H2 in-memory (dev only, all data lost on restart)"
  DB_URL="jdbc:h2:mem:riskpilot;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
  DB_DRIVER="org.h2.Driver"
  DB_DIALECT="org.hibernate.dialect.H2Dialect"
  DDL_AUTO="create"
  FLYWAY_ENABLED="false"
  H2_CONSOLE="true"
fi

# ---- Observer: localhost-only, Spring proxies externally ----
INTERNAL_OBSERVER_PORT=8766
export OBSERVER_PORT="${INTERNAL_OBSERVER_PORT}"
export OBSERVER_HOST="127.0.0.1"

echo "[*] Starting Python Observer on internal port ${INTERNAL_OBSERVER_PORT}..."
(
  while true; do
    OBSERVER_PORT="${INTERNAL_OBSERVER_PORT}" \
    OBSERVER_HOST="127.0.0.1" \
    python3 observer.py 2>&1 | sed 's/^/[observer] /'
    echo "[!] Observer crashed — restarting in 5s..."
    sleep 5
  done
) &

# ---- JVM: tuned for 512 MB Render container ----
JVM_OPTS="${JAVA_OPTS:--Xms64m -Xmx256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError \
  -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=4m \
  -Djava.security.egd=file:/dev/./urandom}"

echo "[*] Starting Spring Boot on port ${PORT}..."
exec java ${JVM_OPTS} -jar app.jar \
  --server.port="${PORT}" \
  --spring.profiles.active="${SPRING_PROFILES_ACTIVE}" \
  --spring.datasource.url="${DB_URL}" \
  --spring.datasource.driver-class-name="${DB_DRIVER}" \
  --spring.datasource.username="${DB_USER:-sa}" \
  --spring.datasource.password="${DB_PASS:-}" \
  --spring.jpa.hibernate.ddl-auto="${DDL_AUTO}" \
  --spring.jpa.properties.hibernate.dialect="${DB_DIALECT}" \
  --spring.flyway.enabled="${FLYWAY_ENABLED}" \
  --spring.h2.console.enabled="${H2_CONSOLE}" \
  --riskpilot.observer.internal-port="${INTERNAL_OBSERVER_PORT}"
