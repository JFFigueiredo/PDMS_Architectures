# PDMS FaaS: OpenFaaS (Lambda-like) and Azure-ready. Same REST API as pdms-monolith.
# Multi-stage: build -> run with of-watchdog. of-watchdog listens on 8080, proxies to our app on 8082.

# -----------------------------------------------------------------------------
# Stage 1: build
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests package

# -----------------------------------------------------------------------------
# Stage 2: of-watchdog (OpenFaaS Lambda-like runtime)
# -----------------------------------------------------------------------------
FROM ghcr.io/openfaas/of-watchdog:0.9.10 AS watchdog

# -----------------------------------------------------------------------------
# Stage 3: run
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

# of-watchdog: listens on 8080 for OpenFaaS gateway, proxies to upstream_url (our app on 8082)
COPY --from=watchdog /fwatchdog /usr/bin/fwatchdog
RUN chmod +x /usr/bin/fwatchdog

RUN groupadd -r pdms && useradd -r -g pdms pdms

COPY --from=builder /build/target/pdms-faas-*.jar ./app.jar
RUN chown -R pdms:pdms /app

# of-watchdog uses 8080 (HTTP) and 8081 (metrics). Our app must use 8082.
ENV SERVER_PORT=8082

# of-watchdog: HTTP mode (proxy to upstream). fprocess starts our JAR; it must bind to upstream port.
ENV mode="http"
ENV upstream_url="http://127.0.0.1:8082"
ENV fprocess="java -jar /app/app.jar"

# Lambda-like: timeouts (env for of-watchdog). Override in stack.yml if needed.
ENV read_timeout="60s"
ENV write_timeout="120s"
ENV exec_timeout="120s"
# Max concurrent requests proxied to Spring per instance. Default 200 aligns with Spring Boot's
# default Tomcat thread cap so cross-architecture benchmarks are not watchdog-serialized. Use "1"
# for strict single-flight (Lambda-like) studies; override via compose/stack env.
ENV max_inflight="200"

USER pdms

EXPOSE 8080

# Override DB/Kafka: SPRING_DATASOURCE_URL, SPRING_KAFKA_BOOTSTRAP_SERVERS (stack.yml or Azure App Settings).
ENTRYPOINT ["/usr/bin/fwatchdog"]
