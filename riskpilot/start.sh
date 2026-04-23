#!/bin/bash

# Set memory limits for Render free tier
export JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseStringDeduplication"

# Boot the Python Observer with memory constraints
echo "[*] Starting Python Observer (memory optimized)... "
OBSERVER_PORT="${OBSERVER_PORT:-8765}" OBSERVER_HOST="${OBSERVER_HOST:-127.0.0.1}" python3 observer.py &

# Boot the Core Java Strategy with optimized memory settings
echo "[*] Starting Shadow Execution Engine (free tier optimized)..."
java $JAVA_OPTS -jar app.jar --server.port="${PORT:-8080}"
