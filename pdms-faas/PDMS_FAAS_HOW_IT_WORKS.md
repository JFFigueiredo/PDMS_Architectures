# PDMS FaaS: How It Works and How It Differs from pdms-monolith

This document describes how **pdms-faas** works, its Lambda-like behaviour on OpenFaaS, and the concrete differences from **pdms-monolith**. Both use the **same database**, **same Kafka**, **same REST API**, and **same features**; only the **runtime**, **deployment**, and **scaling model** differ.

---

## 1. What Is PDMS FaaS?

**pdms-faas** is a variant of PDMS that runs as a **serverless / FaaS-style** application:

- **OpenFaaS (today)**: Uses the **of-watchdog** runtime so it behaves like an OpenFaaS “Lambda”: scale-to-zero, configurable concurrency (`max_inflight`, default 200 for benchmark parity with Tomcat), timeouts, and deployment via `faas-cli` or Kubernetes.
- **Azure (later)**: The same business logic can be behind an Azure Functions HTTP adapter; only the HTTP entrypoint and config source change.

It was created by copying **pdms-monolith** and then:

1. Adding **of-watchdog** in the Docker image as the process that receives HTTP and proxies to the Java app.
2. Adding **OpenFaaSPathStrippingFilter** so the same routes work with and without the `/function/pdms-faas` prefix.
3. Making **server.port** and **config** fully overridable via **environment variables** for FaaS and Docker.
4. Providing **stack.yml** for OpenFaaS with scale-to-zero, timeouts, and `max_inflight`.

The **domain logic** (services, repositories, controllers, DTOs, audit, outbox, JWT) is the same as in pdms-monolith.

---

## 2. How PDMS FaaS Works

### 2.1. Container Layout: of-watchdog + Spring Boot

The run stage of the **Dockerfile** does **not** start the JAR directly. It starts **of-watchdog** (`/usr/bin/fwatchdog`) as `ENTRYPOINT`. of-watchdog then:

1. **Spawns** `fprocess` = `java -jar /app/app.jar` (the Spring Boot app).
2. **Listens** on **port 8080** for HTTP from the OpenFaaS gateway (or from the host when using Docker Compose).
3. **Uses port 8081** internally for its own **metrics** endpoint.
4. **Proxies** each HTTP request to **upstream_url** = `http://127.0.0.1:8082`, where the Spring Boot app listens.

So inside one container:

```
                    ┌─────────────────────────────────────────────────────────┐
                    │                  pdms-faas container                     │
                    │                                                          │
  Request ─────────►│  of-watchdog (PID 1)                                     │
  :8080             │    - Listens :8080 (HTTP)                                │
                    │    - Metrics :8081                                       │
                    │    - Proxies to upstream_url                             │
                    │         │                                                │
                    │         ▼                                                │
                    │  Spring Boot (fprocess)                                  │
                    │    - Listens :8082 (SERVER_PORT=8082)                    │
                    │    - PersonController, ConsentController, SnapshotCtrl   │
                    │    - JWT, Audit, Outbox, Kafka, JDBC                     │
                    │                                                          │
                    └─────────────────────────────────────────────────────────┘
```

- **Outside** the container only **8080** is exposed; 8081 and 8082 are internal.
- **Host mapping** in Docker Compose: `8082:8080` maps the host’s 8082 to the container’s 8080 (of-watchdog), so you call `http://localhost:8082/...` when running without OpenFaaS.

### 2.2. Request Flow (Step by Step)

1. **Client** sends `GET /api/person/{id}` (or, via OpenFaaS gateway, `GET /function/pdms-faas/api/person/{id}`).
2. **of-watchdog** receives the request on 8080, applies `read_timeout`, and **forwards** it to `http://127.0.0.1:8082/api/person/{id}` (or the same path with `/function/pdms-faas` if that was in the URL; the gateway typically passes the path through).
3. **OpenFaaSPathStrippingFilter** (runs first in the Spring filter chain):
   - If the path starts with `/function/pdms-faas`, it wraps the request so `getRequestURI()` and `getServletPath()` return the path **without** that prefix (e.g. `/api/person/{id}`).
   - If the path does not start with that prefix, the request is unchanged.
4. **JwtAuthenticationFilter** validates the `Authorization: Bearer <token>` header (same as monolith).
5. **DispatcherServlet** routes to the correct **Controller** (`PersonController`, `ConsentController`, `SnapshotController`) and the usual **Service** → **Repository** → **DB/Kafka** flow runs.
6. The controller’s response goes back through the filter chain to the HTTP response.
7. of-watchdog **streams** the response to the client, applying `write_timeout` and `exec_timeout`.

So from the app’s point of view, it still sees paths like `/api/person`, `/api/person/{id}/consents`, `/api/snapshot/...`. The only “FaaS-specific” piece in the app is the **path-stripping filter** when the gateway adds `/function/pdms-faas`.

### 2.3. Lambda-like Behaviour (of-watchdog + OpenFaaS)

| Behaviour | How it’s implemented |
|----------|-----------------------|
| **Scale to zero** | In `stack.yml`: `com.openfaas.scale.min: "0"`, `com.openfaas.scale.zero: "true"`. When there is no traffic, OpenFaaS scales the function to 0 replicas. |
| **Scale up under load** | OpenFaaS autoscaler adds replicas when the queue or request rate increases, up to `com.openfaas.scale.max: "10"`. |
| **Concurrency per instance** | `max_inflight` (of-watchdog env): default **200** in this repo so the proxy is not a stricter bottleneck than typical Tomcat defaults when benchmarking against monolith; set `"1"` for strict single-flight per replica. |
| **Timeouts** | `read_timeout`, `write_timeout`, `exec_timeout` (env, also in `stack.yml`). of-watchdog enforces them; if the app exceeds `exec_timeout`, the process can be killed. |
| **Cold start** | When a replica is scaled up from 0, the container starts, of-watchdog starts, then `fprocess` (JVM + Spring Boot) starts. The first request after a cold start pays this cost (JVM + Spring context + DB pool + Kafka client). |
| **Health / readiness** | of-watchdog writes `/tmp/.lock` when it is ready to proxy. Spring’s `/actuator/health` is available on the **upstream** (8082); the gateway or orchestrator can use it for readiness if configured. |

### 2.4. Configuration (Environment Variables)

All runtime configuration is overridable via **environment variables** (e.g. in `stack.yml`, Docker Compose, or, later, Azure App Settings):

| Env | Purpose | Example |
|-----|---------|---------|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://postgres:5432/pdms` |
| `SPRING_DATASOURCE_USERNAME` | DB user | `pdms_user` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | — |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers | `kafka:9092` |
| `SERVER_PORT` | Port the Spring app listens on | `8082` (in container under of-watchdog); `8080` when run locally without watchdog |
| `read_timeout` | of-watchdog: max time to read request | `60s` |
| `write_timeout` | of-watchdog: max time to write response | `120s` |
| `exec_timeout` | of-watchdog: max time for the upstream to respond | `120s` |
| `max_inflight` | of-watchdog: max concurrent requests per instance | `200` (default image/compose; use `1` for strict single-flight) |

JWT uses `jwt.public-key.path=classpath:jwt-keys/public-key.pem` by default (same as monolith); for Azure or custom paths this can be overridden via additional env if the app is extended to read it from env.

### 2.5. Deployment Modes

**A) Docker Compose (no OpenFaaS)**

- `docker compose up -d postgres kafka pdms-faas`
- pdms-faas is a normal service: of-watchdog on 8080 in the container, mapped e.g. to host **8082**.
- Client: `http://localhost:8082/api/person/health`, etc. Same REST contract as monolith.

**B) OpenFaaS (Kubernetes or Docker Swarm)**

- Build: `docker build -t pdms-faas:latest ./pdms-faas`
- Deploy: `faas-cli deploy -f pdms-faas/stack.yml`
- OpenFaaS creates a Deployment/Service and routes `/function/pdms-faas` to the function’s 8080. `stack.yml` sets env (DB, Kafka, timeouts, `max_inflight`) and labels (scale min/max, scale-to-zero).
- Client: `http://<gateway>:8080/function/pdms-faas/api/person/health`. The path-stripping filter turns this into `/api/person/health` for the controllers.

**C) Azure (future)**

- Replace the HTTP entrypoint with an Azure Functions **HttpTrigger** adapter that maps `HttpRequestMessage` → service calls → `HttpResponseMessage`. Config from App Settings / `local.settings.json` (same keys as env). of-watchdog and `stack.yml` are not used.

---

## 3. What Is the Same as pdms-monolith

Everything below is **identical** in behaviour, data, and API:

| Area | Details |
|------|---------|
| **REST API** | Same paths, methods, and semantics: `POST/GET/PUT/DELETE /api/person`, `POST/GET/PUT/DELETE /api/person/{id}/consents`, `POST .../consents/{id}/withdraw`, `GET /api/snapshot/person/{id}/latest`, `GET /api/snapshot/by-email/{email}`, `GET /api/snapshot/by-phone/{phone}`, `GET /api/person/health`, `GET /actuator/health`, etc. |
| **Request/response format** | Same DTOs: `CreatePersonRequestDto`, `CreateConsentRequestDto`. Same JSON for `PersonData`, `Consent`, `PersonSnapshot`, and error bodies. |
| **Database** | Same PostgreSQL schema, same Flyway migrations (`V1__initial_schema.sql`), same tables (`person`, `user_account`, `contact_identity`, `address`, `preferences`, `consent`, `person_snapshot`, `outbox`, audit tables, etc.). |
| **Kafka** | Same producers, serialisation, and topics. Outbox pattern and event shapes unchanged. |
| **Audit** | Same `AuditService`, `AuditSchemaWriter`, `NormalizedAuditWriter`: audit to DB and Kafka in the same way. |
| **JWT** | Same `JwtTokenValidator`, `JwtAuthenticationFilter`, and `classpath:jwt-keys/public-key.pem`. Same 401 and `{"error":"Unauthorized"}` when the token is missing or invalid. |
| **Security** | Same `SecurityConfiguration`: stateless, JWT only, ` permitAll()` for `/actuator/**`, CORS. |
| **Business logic** | Same `PersonService`, `ConsentService`, `PersonSnapshotService`, `PersonChangeEventService`, `ConsentChangeEventService`, `OutboxService`, and repositories (`PersonRepository`, `SimpleConsentMapper`, `SchemaSnapshotWriter`, etc.). |
| **Domain** | Same `PersonData`, `Consent`, `PersonSnapshot`, `Address`, `ContactIdentity`, `UserAccount`, `Preferences`, and supporting types. |

So: **pdms-faas and pdms-monolith are the same application** from the perspective of APIs, data, and features. They can share the **same DB and Kafka** and be swapped without changing clients or schema.

---

## 4. What Is Different from pdms-monolith

### 4.1. Runtime and Process Model

| Aspect | pdms-monolith | pdms-faas |
|--------|---------------|-----------|
| **Entrypoint** | `java -jar app.jar` (Spring Boot is PID 1). | `fwatchdog` (of-watchdog) is PID 1; it starts `java -jar app.jar` as a child process. |
| **HTTP server** | Tomcat **inside** the JVM, bound directly to `server.port` (default 8080). | Tomcat inside the JVM on **8082**; of-watchdog listens on **8080** and **proxies** to 8082. |
| **Ports in container** | One: 8080. | Three used: 8080 (watchdog HTTP), 8081 (watchdog metrics), 8082 (Spring Boot). Only 8080 is exposed. |
| **Lifecycle** | One long‑lived JVM. Stays up until the process is stopped. | of-watchdog starts and supervises the JVM. With OpenFaaS, the whole container can be scaled to 0 and stopped when idle. |

### 4.2. Deployment and Scaling

| Aspect | pdms-monolith | pdms-faas |
|--------|---------------|-----------|
| **Deployment** | One or more instances (e.g. `docker run`, Docker Compose, K8s Deployment) always on. | When using OpenFaaS: deployed as an OpenFaaS **function** via `faas-cli` or Helm; image + `stack.yml`. |
| **Scaling** | Manual or HPA: you decide min/max replicas. No built‑in scale‑to‑zero. | OpenFaaS: `com.openfaas.scale.min: "0"`, `com.openfaas.scale.max: "10"`, `com.openfaas.scale.zero: "true"`. Can scale to 0 when idle. |
| **Concurrency per instance** | Tomcat thread pool: many concurrent requests per JVM. | Default `max_inflight: "200"`: watchdog allows up to 200 concurrent proxied requests per instance (aligned with default Tomcat scale). Use `"1"` to force one-at-a-time per replica. |
| **Cold start** | Only on first deploy or restart. | On each scale‑up from 0: new container + JVM + Spring + DB pool + Kafka. |

### 4.3. Configuration

| Aspect | pdms-monolith | pdms-faas |
|--------|---------------|-----------|
| **Default config** | `application.properties` (and profiles). DB/Kafka often point to `localhost`. | Same `application.properties`, but `server.port=${SERVER_PORT:8080}` and a note that all settings are overridable by env. |
| **Override in Docker** | Env vars for DB/Kafka (e.g. in `docker-compose`). | Same env vars, plus `SERVER_PORT`, and of-watchdog env: `read_timeout`, `write_timeout`, `exec_timeout`, `max_inflight`. |
| **Override in FaaS** | N/A. | `stack.yml` `environment` (and, for OpenFaaS, labels/annotations). For Azure (future): App Settings / `local.settings.json`. |

### 4.4. Code and Build

| Aspect | pdms-monolith | pdms-faas |
|--------|---------------|-----------|
| **Extra Java code** | None. | **OpenFaaSPathStrippingFilter** in `ipl.pdms.faas`: strips `/function/pdms-faas` from the path when present. No other Java differences. |
| **Dockerfile** | Multi‑stage: Maven build → JRE + JAR; `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`. | Multi‑stage: Maven build → **of-watchdog** binary + JRE + JAR; env for `mode`, `upstream_url`, `fprocess`, `SERVER_PORT`, timeouts, `max_inflight`; `ENTRYPOINT ["/usr/bin/fwatchdog"]`. |
| **Artifact** | `pdms-monolith-0.0.1-SNAPSHOT.jar`. | `pdms-faas-0.0.1-SNAPSHOT.jar`. |
| **OpenFaaS** | No. | **stack.yml** for `faas-cli deploy`: image, env, labels (scale.min, scale.max, scale.zero). |

### 4.5. Path and Gateway

| Aspect | pdms-monolith | pdms-faas |
|--------|---------------|-----------|
| **Base path** | `/api/...`, `/actuator/...`. | Same when called directly. When behind OpenFaaS: gateway uses `/function/pdms-faas`; the **path-stripping filter** removes that so controllers still see `/api/...`. |
| **Direct call** | `http://host:8080/api/person/health`. | `http://host:8082/api/person/health` (with Compose mapping 8082→8080). |
| **Via OpenFaaS** | N/A. | `http://gateway:8080/function/pdms-faas/api/person/health`; internally the app sees `/api/person/health`. |

---

## 5. Side‑by‑Side: Request Path

**pdms-monolith:**

```
Client → host:8080 → Tomcat (in JVM) → DispatcherServlet → Controller → Service → DB/Kafka → Response
```

**pdms-faas (Docker Compose):**

```
Client → host:8082 → container:8080 (of-watchdog) → 127.0.0.1:8082 (Tomcat in JVM)
       → OpenFaaSPathStrippingFilter (no-op if path has no /function/pdms-faas)
       → JwtAuthenticationFilter → DispatcherServlet → Controller → Service → DB/Kafka → Response
```

**pdms-faas (OpenFaaS):**

```
Client → Gateway :8080/function/pdms-faas/... → of-watchdog :8080 → 127.0.0.1:8082 (Tomcat)
       → OpenFaaSPathStrippingFilter (strips /function/pdms-faas) → JWT → … → Controller → … → Response
```

---

## 6. When to Use Which

| Use pdms-monolith when | Use pdms-faas when |
|------------------------|---------------------|
| You want a classic, always‑on service. | You want Lambda-like scaling (scale-to-zero, pay‑per‑use style). |
| You run on VMs, Docker, or K8s without FaaS. | You use or plan to use **OpenFaaS** (or Azure Functions later). |
| You prefer a single process and simple lifecycle. | You are fine with an extra hop (watchdog → app) and cold starts when scaling from 0. |
| You need high concurrency per instance (many threads per JVM). | You want one (or few) requests per instance and horizontal scaling, or you are aligning with a FaaS platform. |

---

## 7. Summary

- **pdms-faas** reuses the same **REST API**, **DTOs**, **DB**, **Kafka**, **JWT**, **audit**, and **business logic** as **pdms-monolith**. It is a different **runtime and deployment** for the same product.
- The **differences** are: of-watchdog as PID 1 and HTTP proxy, app on 8082, path-stripping filter for `/function/pdms-faas`, env-driven config, `stack.yml`, and OpenFaaS-specific scaling and timeouts. The **monolith has none of that** and runs as a single JVM bound to 8080.
- Both can share the **same database and Kafka** and be used as two **architecture variants** of the same system for comparison or migration (e.g. to Azure later).
