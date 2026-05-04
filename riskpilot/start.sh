#!/bin/bash

  # Set memory limits for Render free tier
  export JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseStringDeduplication"

  # Fix: Explicitly export OBSERVER_PORT so it never falls back to $PORT
  export OBSERVER_PORT=${OBSERVER_PORT:-8765}

  # Boot the Python Observer and restart it if it crashes.
  echo "[*] Starting Python Observer (supervised) on port $OBSERVER_PORT..."
  (
    while true; do
      OBSERVER_PORT="${OBSERVER_PORT}" OBSERVER_HOST="${OBSERVER_HOST:-0.0.0.0}" python3 observer.py
      echo "[!] Observer crashed, restarting in 5s..."
      sleep 5
    done
  ) &

  # Boot the Core Java Strategy with optimized memory settings
  echo "[*] Starting Shadow Execution Engine (free tier optimized)..."
  java $JAVA_OPTS -jar app.jar --server.port="${PORT:-8080}"
  