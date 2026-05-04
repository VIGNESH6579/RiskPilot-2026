#!/bin/bash

  # Observer port (explicit, not from PORT which is the web server port)
  export OBSERVER_PORT=8765

  # Build PostgreSQL JDBC URL from env vars (or fall back to H2)
  DB_URL="${PG_JDBC_URL:-jdbc:h2:mem:riskpilot;DB_CLOSE_DELAY=-1}"
  DB_USR="${PG_USER:-sa}"
  DB_PWD="${PG_PASS:-}"

  if [[ "$DB_URL" == jdbc:postgresql* ]]; then
    DB_DRIVER="org.postgresql.Driver"
    DB_DIALECT="org.hibernate.dialect.PostgreSQLDialect"
    FLYWAY_ENABLED="true"
  else
    DB_DRIVER="org.h2.Driver"
    DB_DIALECT="org.hibernate.dialect.H2Dialect"
    FLYWAY_ENABLED="false"
  fi

  # Start Observer supervisor
  echo "[*] Starting Python Observer on port $OBSERVER_PORT..."
  (
    while true; do
      OBSERVER_PORT="${OBSERVER_PORT}" OBSERVER_HOST="${OBSERVER_HOST:-0.0.0.0}" python3 observer.py
      echo "[!] Observer crashed, restarting in 5s..."
      sleep 5
    done
  ) &

  # Start Spring Boot — DB config passed as command-line args (highest Spring Boot priority,
  # overrides any auto-injected SPRING_DATASOURCE_URL from Render-linked database)
  echo "[*] Starting RiskPilot Spring Boot on port ${PORT:-8080}..."
  exec java ${JAVA_OPTS} -jar app.jar \
    --server.port="${PORT:-8080}" \
    --spring.datasource.url="${DB_URL}" \
    --spring.datasource.username="${DB_USR}" \
    --spring.datasource.password="${DB_PWD}" \
    --spring.datasource.driver-class-name="${DB_DRIVER}" \
    --spring.jpa.properties.hibernate.dialect="${DB_DIALECT}" \
    --spring.flyway.enabled="${FLYWAY_ENABLED}" \
    --spring.flyway.baseline-on-migrate="true" \
    --spring.flyway.url="${DB_URL}" \
    --spring.flyway.user="${DB_USR}" \
    --spring.flyway.password="${DB_PWD}"
  