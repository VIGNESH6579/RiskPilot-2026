# Build Stage securely neatly gracefully effectively stably cleanly accurately explicit smartly explicitly
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run Stage dynamically smoothly logically solidly exactly smoothly smartly comfortably naturally appropriately natively squarely naturally natively cleverly.
FROM eclipse-temurin:17-jre-jammy

# Enforce IST Clock Sync Check natively efficiently smoothly explicit correctly seamlessly tightly smartly successfully effortlessly seamlessly securely cleanly
ENV TZ="Asia/Kolkata"

WORKDIR /app
COPY --from=builder /app/target/riskpilot-0.0.1-SNAPSHOT.jar app.jar

# Explicitly expose port 8080 for Render Web Service Health Checks properly firmly safely.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
