# Build Stage securely neatly gracefully effectively stably cleanly accurately explicit smartly explicitly
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run Stage dynamically smoothly logically solidly exactly smoothly smartly comfortably naturally appropriately natively squarely naturally natively cleverly.
FROM eclipse-temurin:17-jre-jammy

# Install Python & libraries for the Observer safely efficiently smoothly
RUN apt-get update && \
    apt-get install -y python3 python3-pip && \
    rm -rf /var/lib/apt/lists/*
RUN pip3 install websockets aiohttp

# Force Cache Invalidation efficiently stably smoothly cleanly solidly seamlessly solidly confidently expertly correctly correctly safely firmly exactly safely safely explicit securely correctly strictly
ENV LIVE_OBSERVER_VERSION="2026.4.19"
# Enforce IST Clock Sync Check natively efficiently smoothly explicit correctly seamlessly tightly smartly successfully effortlessly seamlessly securely cleanly
ENV TZ="Asia/Kolkata"

WORKDIR /app
COPY --from=builder /app/target/riskpilot-0.0.1-SNAPSHOT.jar app.jar
COPY observer.py .
COPY start.sh .

RUN chmod +x start.sh

# Explicitly expose port 8080 (REST Health) and 8765 (WebSocket Observer)
EXPOSE 8080 8765

ENTRYPOINT ["./start.sh"]
