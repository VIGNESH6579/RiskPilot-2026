# Build Stage optimized for Render free tier
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# Set Maven memory limits for free tier
ENV MAVEN_OPTS="-Xms256m -Xmx512m"

COPY pom.xml .
COPY src ./src

# Build with optimized settings for free tier
RUN mvn clean package -DskipTests -Dmaven.test.skip=true -Dspring-boot.repack.skip=false

FROM eclipse-temurin:17-jre-jammy

# Install Python and observer libraries.
RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 python3-pip && \
    rm -rf /var/lib/apt/lists/*
COPY requirements-observer.txt /tmp/requirements-observer.txt
RUN PIP_BREAK_SYSTEM_PACKAGES=1 python3 -m pip install --no-cache-dir -r /tmp/requirements-observer.txt

ENV LIVE_OBSERVER_VERSION="2026.4.19"
ENV TZ="Asia/Kolkata"

WORKDIR /app
COPY --from=builder /app/target/riskpilot-*.jar /app/app.jar
COPY observer.py .
COPY frontend.html .
COPY start.sh .

RUN chmod +x start.sh

# Explicitly expose port 8080 (REST Health) and 8765 (WebSocket Observer)
EXPOSE 8080 8765

ENTRYPOINT ["./start.sh"]
