FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

ENV MAVEN_OPTS="-Xms256m -Xmx512m"

COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn ./.mvn
COPY src ./src

RUN mvn clean package -DskipTests -Dmaven.test.skip=true -Dspring-boot.repack.skip=false

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseStringDeduplication"
ENV TZ="Asia/Kolkata"

COPY --from=builder /app/target/riskpilot-1.0.0-PRODUCTION.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
