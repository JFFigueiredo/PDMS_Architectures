# Multi-stage build — Java 25, matching all other PDMS variants.

# -----------------------------------------------------------------------------
# Stage 1: build
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests package

# -----------------------------------------------------------------------------
# Stage 2: run
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

RUN groupadd -r pdms && useradd -r -g pdms pdms

COPY --from=builder /build/target/pdms-hybrid-decentralized-*.jar ./app.jar

RUN chown -R pdms:pdms /app
USER pdms

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
