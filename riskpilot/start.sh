#!/bin/bash

  # Observer port (never use $PORT — that's the HTTP server port)
  export OBSERVER_PORT=8765

  # Start Python Observer with auto-restart
  echo "[*] Starting Python Observer on port $OBSERVER_PORT..."
  (
    while true; do
      OBSERVER_PORT="${OBSERVER_PORT}" OBSERVER_HOST="${OBSERVER_HOST:-0.0.0.0}" python3 observer.py
      echo "[!] Observer crashed, restarting in 5s..."
      sleep 5
    done
  ) &

  # Start Spring Boot.
  # We pass DB config as command-line args (highest Spring Boot priority — overrides all env vars).
  # We use H2 in-memory DB with PostgreSQL-compatible mode so Flyway and JPA work without
  # needing a real PostgreSQL connection. Real-time market data comes from Angel One API.
  echo "[*] Starting RiskPilot Spring Boot on port ${PORT:-8080}..."
  exec java ${JAVA_OPTS} -jar app.jar \
    --server.port="${PORT:-8080}" \
    --spring.datasource.url="jdbc:h2:mem:riskpilot;DB_CLOSE_DELAY=-1;MODE=PostgreSQL" \
    --spring.datasource.username="sa" \
    --spring.datasource.password="" \
    --spring.datasource.driver-class-name="org.h2.Driver" \
    --spring.jpa.hibernate.ddl-auto="create" \
    --spring.jpa.properties.hibernate.dialect="org.hibernate.dialect.H2Dialect" \
    --spring.flyway.enabled="false"
  