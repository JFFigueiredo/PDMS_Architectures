# PDMS FaaS (OpenFaaS, Lambda-like; Azure-ready)

Same **REST API**, **input/output formats**, and **features** as `pdms-monolith`; same **PostgreSQL** and **Kafka**. Different software architecture: runs as an OpenFaaS function (Lambda-like) and is structured so it can be moved to **Azure Functions** later.

## Lambda-like behavior (OpenFaaS)

- **of-watchdog**: OpenFaaS runtime in the image. Listens on 8080, proxies to the app on 8082.
- **Scale to zero (Kubernetes / faasd)**: `stack.yml` sets `com.openfaas.scale.min: "0"`, `com.openfaas.scale.zero: "true"`, and `com.openfaas.scale.zero-duration` so replicas can sit at **zero** when idle, scale up on requests, then return to zero after the quiet period. Your cluster must run an autoscaler that honors these labels (see [OpenFaaS autoscaling](https://docs.openfaas.com/architecture/autoscaling)); the gateway should allow scaling from zero (`scale_from_zero`, default on K8s/faasd).
- **Docker Compose** (`docker compose … pdms-faas`): one container stays up; that is **not** the same as zero pods—use `faas-cli deploy` to OpenFaaS for true scale-to-zero.
- **Concurrency at the watchdog**: `max_inflight` defaults to **200** (same order as Spring Boot’s default Tomcat cap) so benchmarks against other architectures are not limited to one request at a time. Set `max_inflight: "1"` in compose or `stack.yml` only when you want strict single-flight per instance.
- **Timeouts**: `read_timeout`, `write_timeout`, `exec_timeout` (env / stack.yml).

## Run with Docker Compose (no OpenFaaS)

```bash
# From PDMS root
docker compose up -d postgres kafka pdms-faas
```

- App: **http://localhost:8082** (host 8082 → container 8080, of-watchdog).
- Same endpoints as monolith: `/api/person`, `/api/person/{id}/consents`, `/api/snapshot/...`, `/actuator/health`.

## Run with OpenFaaS

1. Install OpenFaaS and `faas-cli`.
2. Start Postgres and Kafka (or point env to your instances).
3. Build and deploy:

   ```bash
   docker build -t pdms-faas:latest ./pdms-faas
   faas-cli deploy -f pdms-faas/stack.yml
   ```

4. Call the function via the OpenFaaS gateway, e.g.  
   `http://<gateway>:8080/function/pdms-faas/api/person/health`  
   (the `/function/pdms-faas` prefix is stripped inside the app).

## Load testing through the gateway (without changing the canonical JMX)

The repo’s main JMeter plan (`jmeter/scripts/PDMS-Combined-Benchmark.jmx`) is tuned for a direct app URL (no `/function/pdms-faas` prefix). To stress the OpenFaaS gateway **without** editing that file, use a **separate** test plan, a **reverse proxy** that strips or rewrites paths, or another client (e.g. k6) that calls `http://<gateway>:8080/function/pdms-faas/api/...`.

Scale-to-zero applies when the function is deployed via OpenFaaS with the labels in `stack.yml`, not when using Docker Compose alone.

## Config (env)

- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`
- `SERVER_PORT` (default 8082 in-container when using of-watchdog; 8080 when run locally).

Same keys work for OpenFaaS (`stack.yml`), Docker Compose, and later **Azure** (App Settings / `local.settings.json`).

## Azure later

The HTTP layer is a thin adapter. For Azure Functions, add an adapter that maps `HttpRequestMessage` → existing services → `HttpResponseMessage` and reuses this project’s services, repositories, and config.
