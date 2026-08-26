# Multi-stage: build then run. First build needs network for Maven deps; the final image runs offline.
# Java 25 to match pdms-monolith

# -----------------------------------------------------------------------------
# Stage 1: build
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build

# Copy only what Maven needs (faster layer reuse)
COPY pom.xml .
COPY src ./src

# Build; skip tests for smaller/faster image (run tests in CI or locally)
# First build: Maven downloads deps (needs network). After that, the image is self-contained.
RUN mvn -B -DskipTests package

# -----------------------------------------------------------------------------
# Stage 2: run
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

# Non-root user
RUN groupadd -r pdms && useradd -r -g pdms pdms

# JAR from builder (Spring Boot fat JAR)
COPY --from=builder /build/target/pdms-monolith-*.jar ./app.jar

RUN chown -R pdms:pdms /app
USER pdms

EXPOSE 8080

# Default: run the app. Override DB/Kafka via env in docker-compose.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
