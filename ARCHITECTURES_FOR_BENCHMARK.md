# PDMS – Software Architectures for Benchmarking (Same DB)

This document describes **software architectures that use the same shared database** and have a **measurable impact on runtime performance**—beyond moving code between folders. The goal is to benchmark these so you can compare throughput, latency, and resource usage under identical data and workload.

---

## 0. Current Benchmark Variants and Data Collection (as implemented now)

This section reflects the variants and operational scripts that are currently wired in this repository.

### 0.1 Runnable application variants (`restart_all_services.sh`)

The restart script currently supports:

- `monolith`
- `microservices`
- `microservices-async`
- `faas`
- `decentralized`

Notes about current deployment wiring:

- `decentralized` uses `docker-compose.decentralized.yml` for the app and points to the DB/Kafka VM.
- **Besu runs on its own dedicated VM since 2026-08** (previously co-located on the DB VM). It is
  reached as `<ssh-user>@<besu-host>`; Besu compose is searched at `~/docker-compose.besu.yml`
  first, then `~/PDMS/docker-compose.besu.yml`. Both `restart_all_services.sh` and
  `collect_all_remote_data.sh` must use the `<ssh-user>` account — `<ssh-user>` has no
  `docker.sock` permission on that host and collection fails silently under it.
- For `decentralized`, the Besu chain is wiped per run (`down --remove-orphans` +
  `up -d --build --force-recreate`); chain data lives in the container layer, so recreation is a
  clean chain.
- DB/Kafka restart uses `docker-compose_DB_KAFKA.yml` on the DB VM and wipes volumes
  (`down -v`) during restart. The app stack is also brought down with `down -v`, so **every
  repetition starts from an empty database and an empty chain**.

### 0.2 Data captured per benchmark run (`collect_all_remote_data.sh`)

For each run, output includes:

- `app/` — app logs/artifacts from app VM.
- `dbAndKafka/` — DB/Kafka artifacts and Kafka topic inventory export.
- `prometheus/` — Prometheus/Grafana metrics artifact for the selected time range.
- `jmeter.zip` — single compressed JMeter artifact bundle (all collected `.jtl` files).
- `hostMetrics/` — per-VM host metrics bundles (`app`, `db`, `jmeter`, `prom`, and
  **`besu` for `decentralized` only**).
- `besu/` — only for variant `decentralized` (Besu container/runtime diagnostics, RPC snapshots,
  `docker_stats_series.csv`, and `besu_prometheus_metrics.txt`).

**Expected file count per repetition** (verified by counting the collected reps):

| Architecture | Files | Composition |
|---|---|---|
| `monolith`, `faas`, `microservices` | **177** | baseline |
| `microservices-async` | **180** | 177 + 3 `kafka_pdms_*_messages.jsonl` |
| `decentralized` | **205** | 177 + 20 in `besu/` + 8 in `hostMetrics/host_metrics_besu_*` |

Note the collector only produces the three `kafka_pdms_*_messages.jsonl` files for
`microservices-async`; the other four architectures have none. Those files are the known-broken
ones (see §0.4), so their absence costs no information — but it does mean **177, not 180, is the
baseline**. `auto_collect.sh` validates this per architecture and additionally asserts
that `host_metrics_besu_*` and `besu/besu_data_*` exist for `decentralized`.

Host metrics detail:

- Last-hour CPU/RAM is collected from each VM using `sar` when `sysstat` archives are available.
- If `sysstat` is missing or not collecting, point-in-time fallback snapshots are still captured (`free`, `vmstat`, load, optional `docker stats`).

---

## 0.3 Benchmark configuration of record (2026-08-14)

Everything in this subsection is the configuration under which the final 75-repetition campaign
was collected. Values were read from the compose files, `application.properties`, and the
`docker_inspect.json` captured inside the run artifacts — not from documentation.

### Infrastructure

Five Azure VMs, all `Standard_D2s_v6` (2 vCPU, 8 GiB RAM, 8 GiB data disk, max 3750 IOPS,
~$0.11/hour), all in **Sweden Central**, all **UTC**.

| Role | Host | Used by |
|------|------|---------|
| Application | `<app-host>` | all architectures |
| DB + Kafka | `<db-host>` | all architectures |
| JMeter (load generator) | `<jmeter-host>` | all architectures |
| Observability (Prometheus/Grafana) | `<observability-host>` | all architectures |
| **Besu (blockchain)** | `<besu-host>` (`<ssh-user>`) | **`decentralized` only** |

> **Fairness note.** `decentralized` consumes a fifth VM that no other architecture uses. This is
> inherent — a blockchain-anchored architecture needs a node — but it means "identical resources"
> is not literally true across all five architectures. Report the **total resource footprint**
> per architecture, and treat Besu's CPU/RAM (captured in `docker_stats_series.csv` and the
> `besu_*` Prometheus metrics) as part of the cost of `decentralized`.

### Container resource limits

CPU and memory limits are set so that **every architecture receives the same application-side
budget: 1.8 CPU and 6144 MB**. For microservices this budget is split across three services.

| Architecture | Container | CPU limit | Memory limit | CPU reservation | Memory reservation |
|---|---|---|---|---|---|
| monolith | `pdms-monolith` | 1.8 | 6144M | 1.5 | 4096M |
| faas | `pdms-faas` | 1.8 | 6144M | 1.5 | 4096M |
| decentralized | `pdms-hybrid-decentralized` | 1.8 | 6144M | 1.5 | 4096M |
| microservices | `pdms-person` | 1.0 | 2048M | 0.67 | 1200M |
| microservices | `pdms-snapshot` | 0.5 | 2048M | 0.33 | 1200M |
| microservices | `pdms-consent` | 0.3 | 2048M | 0.20 | 1200M |
| microservices-async | `pdms-person-async` | 1.0 | 2048M | 0.67 | 1200M |
| microservices-async | `pdms-snapshot-async` | 0.5 | 2048M | 0.33 | 1200M |
| microservices-async | `pdms-consent-async` | 0.3 | 2048M | 0.20 | 1200M |
| *(shared infra)* | `postgres` | 1.0 | 3072M | 0.5 | 1536M |
| *(shared infra)* | `kafka` | 0.8 | 2048M | 0.4 | 1024M |
| *(decentralized only)* | `pdms-decentralized-besu` | 1.8 | 6144M | 1.5 | 4096M |

Microservices CPU sums to **1.0 + 0.5 + 0.3 = 1.8**, matching the monolithic budget. The 1.0/0.5/0.3
split was **chosen a priori**, before any per-service load was measured — no measurement supports it,
and none should be cited for it. The measured outcome is in fact the signature of a
**disproportionate** split: under load `person` runs at **99.8%** of its quota while `snapshot` and
`consent` sit at **40.1%** and **39.6%** of theirs. It is a fixed allocation, so microservices cannot
reallocate CPU across services the way a monolith reallocates threads. That rigidity is a genuine
architectural property, not a measurement artifact, and should be stated as such.

Verify applied limits from any run artifact rather than trusting compose:

```bash
docker inspect <container> --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}'
# monolith/faas/decentralized/besu expect: 1800000000 6442450944
```

### Database connection pools

PostgreSQL runs with `max_connections=250` (set via `command: postgres -c max_connections=250`).
Each architecture is given a **total application pool of 200**, leaving headroom for `pg_dump`
during collection and for administrative sessions.

| Project | `maximum-pool-size` | `minimum-idle` |
|---|---|---|
| `pdms-monolith` | 200 | 67 |
| `pdms-faas` | 200 | 67 |
| `pdms-decentralized` | 200 | 67 |
| `pdms-microservice-person` / `-async` | 90 | 30 |
| `pdms-microservice-snapshot` / `-async` | 70 | 23 |
| `pdms-microservice-consent` / `-async` | 40 | 13 |

Microservices sum to **90 + 70 + 40 = 200**, matching the monolithic total.

### JVM

Java **25.0.3+9** (`eclipse-temurin`), no explicit heap flags. The JVM is container-aware and
derives both heap (`MaxRAMPercentage`, default 25%) and `availableProcessors()` from the cgroup
limits above. Because the CPU limit differs per microservice, each microservice JVM also sizes its
internal thread pools differently — a further consequence of the fixed CPU split.

### Workload and load ladder

| Parameter | Value |
|---|---|
| Warm-up | 5 threads / 120 s — **results discarded**, followed by 30 s settle |
| Load ladder | **2, 5, 15, 50 threads** |
| Duration per step | 300 s |
| Ramp-up per step | 30 s |
| Pause between steps | 30 s |
| Artifacts per run | 5 `.jtl` files (1 warm-up + 4 steps) |
| Repetitions | 5 per scenario |
| Scenarios | `mix` (step-load), `read` (80% reads / 20% writes), `writte` (80% writes / 20% reads) |

The ladder deliberately starts at **2 threads** so that per-request cost can be measured before
saturation. Measured CPU of the **primary application container**, `mix` scenario, mean over reps
1–5 (window = step start + 40 s to + 300 s, i.e. ramp trimmed; source
`prometheus/*/cpu_usage_pct.json`, n = 68–90 samples per cell):

| Architecture | 2 thr | 5 thr | 15 thr | 50 thr |
|---|---|---|---|---|
| `monolith` | **56.9%** | 91.8% | 99.4% | 99.5% |
| `faas` | **62.8%** | 99.3% | 99.9% | 99.9% |
| `decentralized` | **56.1%** | 94.6% | 96.5% | 98.6% |
| `microservices` (person) | **69.7%** | 98.3% | 99.5% | 99.8% |
| `microservices-async` (person) | **88.1%** | 99.6% | 99.9% | 99.8% |

So the "unsaturated first step" holds cleanly for the three single-deployable architectures
(56–63%), which reach only **60–67%** of their own 50-thread throughput at 2 threads. **It does not
hold for either three-service variant.** Measured as fraction of its own 50-thread throughput, at
2 threads `microservices` is already at **70.4%** (mix) / **83.0%** (read), and
`microservices-async` at **89.0%** (mix) — in read-heavy the async variant is *faster* at 2 threads
than at 50 (**384.6 vs 343.9 rps**), and its primary container is at **88.1% mean (97.9% peak)** of
quota. From 5 threads upward every primary container is effectively saturated, so those steps
describe throughput ceilings rather than per-request cost.

> **Per-request cost is not recoverable for `microservices` or `microservices-async` at any rung.**
> At 2 threads they are already close to their ceiling; at 5/15/50 their summed CPU is frozen at
> ~72–74% of the 1.8 budget (below). There is no rung at which a CPU-ms/request figure for these
> two isolates architectural cost rather than the stranded fixed split. Quote the table below for
> the three single-deployable variants; for the two three-service variants treat it as an upper
> bound on efficiency, not a measurement.

**The secondary microservices never saturate.** In sync `microservices`, `snapshot` (8081) and
`consent` (8082) sit at ~32–42% mean at *every* load level, peaking at 47–78%, while `person` is
pinned at ~100%. In `microservices-async` the same holds from 5 threads upward (39.7–41.5% mean),
with **one exception**: async `consent` at 2 threads averages **54.1% with a 94.7% peak** — but that
series decays monotonically across the window (≈95% → ≈40%), i.e. it is a warm-up/JIT tail bleeding
past the 40 s trim, not steady state. Do not quote "32–42% at every load level" for the async
variant.

The consequence is the same either way, and it is measurable in aggregate: summed over their three
containers, **both three-service variants plateau at ~72–74% of the 1.8 CPU budget from 5 threads
upward and never move**, while the single-deployables convert the same budget into 92–100%. The
fixed 1.0/0.5/0.3 split therefore starves the hot service while ~0.8 CPU sits stranded in the other
two — a monolith with the same total budget reallocates freely. This is the clearest single argument
for why the microservices numbers look the way they do, and it is a property of fixed allocation,
not of measurement.

**Per-request CPU cost at 2 threads** (`mix`, mean of reps 1–5), computed as
Σ(cpu_usage_pct/100 × container CPU limit) × step duration ÷ requests-in-step:

| Architecture | CPU-ms / request (recipe above) | window-consistent |
|---|---|---|
| `monolith` | **4.34** | 4.21 |
| `decentralized` | 4.37 | 4.21 |
| `faas` | 4.87 | 4.71 |
| `microservices` | **5.89** | 5.60 |
| `microservices-async` | 6.04 | 5.68 |

Microservices costs ≈ **1.36×** the monolith per request (**1.33×** window-consistent), which is what
the inter-service HTTP hop buys. Reproduce with the step windows from `General` and the JSON above.

Three caveats on this table:

1. **The recipe as written is internally inconsistent.** CPU is averaged over the ramp-trimmed
   `[+40 s, +300 s]` window, but requests-in-step is the *whole* 300 s total from `General`, which
   includes the slower ramp. That inflates every figure by ~3–5%. The **window-consistent** column
   recomputes both sides over `[+40 s, +300 s)` with duration 260 s. Report one column or the other,
   never a mix. (Dividing the unadjusted 300 s request count by 270 s is wrong in the other
   direction — it yields 3.91/3.93/4.38/5.30/5.43 and should not be used.)
2. **`decentralized` excludes Besu.** `cpu_usage_pct.json` for that variant carries only the
   `pdms-app` job; Besu runs on a fifth VM and its artifacts are point-in-time snapshots, not a
   time series, so no windowed mean exists for it. The near-parity with `monolith` (1.006×) is an
   artifact of omitting the chain, and contradicts this document's own instruction to count Besu as
   part of `decentralized`'s cost.
3. **`microservices-async` counts failures as requests.** It runs 2.1–2.5% errors at 2 threads while
   every other variant runs 0.0%; per *successful* request it is ~2.3% more expensive again.

### Campaign totals

| Architecture | mix | read | writte | Files/rep |
|---|---|---|---|---|
| `faas` | 5 | 5 | 5 | 177 |
| `monolith` | 5 | 5 | 5 | 177 |
| `microservices` | 5 | 5 | 5 | 177 |
| `microservices-async` | 5 | 5 | 5 | 180 |
| `decentralized` | 5 | 5 | 5 | **205** |

**75 repetitions total — collection completed 2026-08-14 16:08, all 75 validated with 0 problems**
(see `data/validation_report.md` Addendum 8). Per-repetition isolation: app and DB/Kafka stacks are recreated with
`down -v` and Besu is force-recreated, so no state carries between repetitions.

---

## 0.4 Correctness fixes and behaviours that affect interpretation

These are properties of the code and the harness that a reader of the results needs in order to
interpret them correctly. Each was verified against collected artifacts.

### Fixed before the final campaign

- **`AuditService` async self-invocation (6 projects).** The `HttpServletRequest` overload was
  annotated `@Async @Transactional` and called internally, so the Spring proxy was bypassed and the
  request-scoped `HttpServletRequest` was recycled on the async thread, throwing on every audit
  write. The overload now delegates to the `String[]` variant. Fixed in `faas`,
  `microservice-snapshot`, and the three `-async` services.
  **Verification:** 0 `AuditService` exceptions across all 75 repetitions.
- **Besu collection under the wrong SSH user.** `collect_all_remote_data.sh` used `<ssh-user>@`
  while `restart_all_services.sh` used `<ssh-user>@`; the former has no `docker.sock` access on the
  Besu VM.
- **Besu build context missing on its VM.** `docker/besu/Dockerfile` had never been copied to the
  dedicated VM, so `docker compose up --build` failed with
  `unable to prepare context: path "/home/<ssh-user>/docker/besu" not found`.
- **Besu metrics were not enabled.** `--metrics-enabled` / `--metrics-host` / `--metrics-port=9545`
  added to the Dockerfile and `9545:9545` published in `docker-compose.besu.yml` (the collector
  curls `127.0.0.1:9545/metrics` **from the VM host**, so exposing the port in the image is not
  sufficient). Each `decentralized` repetition now carries
  `besu_prometheus_metrics.txt` with **153 `besu_*` metrics**. Enabling it did not perturb results
  (**8/12/36/119 ms before vs 7/12/35/118 ms after** — one repetition each, so this is within
  run-to-run variance rather than a demonstration of zero cost; the pre-metrics run's artifacts
  were deleted and only its runner log survives, at
  `scripts/data_collector/detached_dec_mix1_082616.log`).
- **Harness robustness:** SSH keepalives (`ConnectTimeout=20`, `ServerAliveInterval=15 ×8`) on the
  host-metrics transfers after a half-open TCP stream hung a collection for 36 minutes; the collect
  retry now wipes the partial staging folder first; the hostMetrics folder-count check is
  architecture-aware (4 hosts, 5 for `decentralized`).

### Behaviours to report, not to "fix"

- **`microservices-async` returns snapshot 404s on the read path.** Snapshot creation is
  fire-and-forget, so a read can outrun the write. Mix: errors *decay* with load
  (2.5% → 0.3%) because contention lengthens the window in which the snapshot lands. Read-heavy:
  errors are stable at 0.6–0.9% for the first three steps then rise at 50 threads (1.4–3.1%) as the
  snapshot service saturates. `decentralized` shows **0 errors** on the same read workload, which
  makes this a clean architectural contrast rather than a defect.
- **`decentralized` blockchain work continues after the load window closes.** Anchoring was still
  draining at ~57 anchors/min twenty minutes after JMeter stopped. Throughput measured inside the
  window therefore **understates** the true per-request cost of this architecture; some of the work
  is paid later. Per-repetition wipes mean it does not contaminate the next repetition.
- **Each consent produces 3 on-chain anchors** (one per state transition: create/update/withdraw),
  each a distinct transaction. This is by design, not double-anchoring.
- **The `decentralized` container reports `unhealthy`.** Its Docker healthcheck shells out to
  `curl`, which is absent from the image (`/bin/sh: 1: curl: not found`). The application is
  healthy; the harness gates on an independent HTTP poll of the service port. Cosmetic only.
- **Kafka message dumps are empty.** `kafka_pdms_*_messages.jsonl` contain a shell error
  (`kafka-console-consumer.sh: executable file not found`) rather than messages. **Cause:**
  `collect_db_kafka.sh:23` calls `kafka-console-consumer.sh`, while the Confluent image ships
  `/usr/bin/kafka-console-consumer` without the `.sh` suffix. The **topic inventory** is collected
  correctly and is what the validation checks; per-message payloads are not available for this
  campaign. Backing-service configuration (PostgreSQL settings, Kafka topology, Prometheus scrape
  interval) is documented in `data/VMs/VM_INFRASTRUCTURE_2026-08-14.md` §6b.

---

## 1. What “Same DB”, “Performance-Impact”, and “Switchable” Mean

- **Same DB**: Same PostgreSQL instance, same schema (or backward-compatible extensions like read-model tables). No database-per-service; the variable is the **application architecture**, not the data topology.
- **Performance impact**: The architecture changes **how** the app uses CPU, memory, I/O, and the network. Examples: concurrency model, blocking vs non-blocking I/O, caching, serialization protocol.
- **Switchable**: You must be able to **switch from one architecture to another** without irreversible data model changes. The database stays in a **normalized, CRUD-friendly form** that any variant can read and write. You can replace the app (or a config/profile) and point it at the same tables. **Event Sourcing is excluded**: the source of truth becomes an event store; switching back to CRUD would require a one-time migration to rebuild current state—it is not reversibly switchable.

---

## 2. Architectures You Already Have (Reference)

| Architecture / Variant | Script Name | DB Topology (current benchmark ops) | Execution / I/O | Main performance levers |
|------------------------|-------------|--------------------------------------|-----------------|-------------------------|
| Monolith | `monolith` | Shared DB/Kafka infra VM | Blocking (Tomcat, JDBC) | Thread pool, connection pool |
| Microservices (Sync) | `microservices` | Existing microservices setup (as configured in compose/scripts) | HTTP between services + blocking DB calls | Service-to-service RTT, per-service pools |
| Microservices (Async) | `microservices-async` | Existing microservices async setup | **Same HTTP call graph as `microservices`**; `.block()` → `.subscribe(...)` on the snapshot write. **No Kafka consumers exist** (`@KafkaListener` returns zero across every service tree); Kafka is publish-only outbox/audit traffic, identical in all five variants | Fire-and-forget vs. blocking on the inter-service hop; unobserved write completion |
| FaaS | `faas` | Shared DB/Kafka infra VM (current remote compose wiring) | Function-style handlers / containerized runtime | Cold/warm behavior, startup overhead |
| Decentralized + Besu | `decentralized` | Shared DB/Kafka infra VM + **Besu on its own dedicated VM** (since 2026-08) | App + blockchain interaction path | Besu processing overhead + messaging/I/O mix |

---

## 3. Additional Architectures (Same Shared DB, Different Runtime)

These use the **same shared PostgreSQL** and same (or extended) schema, but change execution model, I/O, or access patterns in ways that affect performance.

---

### 3.1. Monolith / Modular Monolith + **Virtual Threads (Project Loom)**

**What changes**

- **Request handling**: Each request runs on a **virtual thread** instead of a platform (OS) thread. Same Servlet, same `JdbcTemplate`/JDBC, same business logic.
- **DB**: Same PostgreSQL, same HikariCP. You can typically **increase** `maximum-pool-size` because virtual threads block cheaply and don’t hold an OS thread while waiting on I/O.

**Config (conceptually)**

```properties
# Spring Boot 3.2+
spring.threads.virtual.enabled=true
# Optionally: larger pool, since blocking on a VT is cheap
spring.datasource.hikari.maximum-pool-size=200
```

**Expected performance impact**

- **Throughput**: Often higher under high concurrency (many concurrent requests without proportional OS-thread growth).
- **Latency**: Similar or slightly better; fewer thread-pool queueing effects.
- **Memory**: Lower than “one OS thread per request” at scale.

**Effort**: **Low** – enable virtual threads on an existing `pdms-monolith` and re-run the same JMeter plan.

**Suggested module**: `pdms-monolith-virtual-threads` or a profile/flag on `pdms-monolith`.

---

### 3.2. **Reactive stack (WebFlux + R2DBC)**

**What changes**

- **HTTP**: Tomcat → **Netty** (non-blocking).
- **DB**: JDBC → **R2DBC** (reactive PostgreSQL driver). Same schema; only the access API changes (`DatabaseClient` / `R2dbcEntityTemplate` instead of `JdbcTemplate`).
- **Code**: Controllers return `Mono`/`Flux`; services and repositories use reactive types end-to-end.

**Expected performance impact**

- **Throughput**: Can be higher under very high concurrency; fewer threads, better CPU use when I/O-bound.
- **Latency**: Different tail behavior; backpressure can smooth load.
- **Memory**: Generally lower per connection (non-blocking), fewer threads.

**Effort**: **High** – new module or variant; all DB access and some services need a reactive rewrite. Same Flyway migrations and tables.

**Suggested module**: `pdms-monolith-reactive` or `pdms-modular-monolith-reactive`.

---

### 3.3. **Cache-aside / Write-through (e.g. Caffeine + DB)**

**What changes**

- **Reads**: Application checks **in-process cache (Caffeine)** or **Redis** first; on miss, reads from PostgreSQL and populates cache.
- **Writes**: Depending on strategy: invalidate on write, or write-through to cache and DB. DB remains source of truth.
- **DB**: Same PostgreSQL; same schema. Cache is an extra layer in front.

**Expected performance impact**

- **Read latency & throughput**: Much better for repeated reads of the same entities (person, consent, snapshot).
- **Write latency**: Similar to no-cache; cache invalidation adds a small cost.
- **DB load**: Lower for read-heavy workloads.

**Effort**: **Medium** – add Caffeine (or Redis) to an existing monolith; wire `PersonService`, `ConsentService`, snapshot reads through a cache adapter. Same DB, same endpoints.

**Suggested module**: `pdms-monolith-cache`.

---

### 3.4. **gRPC instead of REST**

**What changes**

- **API layer**: REST/JSON → **gRPC** with **Protocol Buffers**. Same operations (create person, get person, consents, snapshot); different transport and serialization.
- **DB**: Unchanged; gRPC controllers call the same services/repositories.

**Expected performance impact**

- **Serialization**: Protobuf is typically faster and smaller than JSON.
- **Transport**: HTTP/2, multiplexing, often fewer round trips.
- **Latency & throughput**: Often 20–40% better for the same logic, especially for smaller payloads and many calls.

**Effort**: **Medium** – define `.proto`, generate server stubs; implement gRPC handlers that delegate to existing services. JMeter would need a gRPC plugin or a separate gRPC client for benchmarking.

**Suggested module**: `pdms-monolith-grpc` (or a separate port in the same app with gRPC server).

---

### 3.5. **CQRS with materialized read model (same DB)**

**What changes**

- **Writes**: Same as today (or event-sourced). **Read model** is updated synchronously or asynchronously (e.g. in same TX or via an outbox/consumer).
- **Reads**: Serve from **dedicated read tables/views** (denormalized, optimized for queries) instead of querying the normalized write model. Same DB instance, different schemas/tables.
- **DB**: Same PostgreSQL; e.g. `person_read`, `consent_read`, `snapshot_read` optimized for `GET /person/{id}`, `GET /person/{id}/consents`, etc.

**Expected performance impact**

- **Read latency & throughput**: Lower query complexity, fewer JOINs; can be significantly faster for complex person/consent/snapshot responses.
- **Writes**: Slightly more work (update write model + read model); if async, write latency stays similar.

**Effort**: **Medium–High** – the command/query split and a dedicated materialized read model both have to be built, and reads wired to it.

**Suggested module**: `pdms-monolith-materialized-read`, extending `pdms-monolith` with a `*-read` schema.

**Switchable**: ✅ Source of truth stays in normalized tables; read model is additive. To switch back: stop updating read tables and query the main tables.

---

### 3.6. **GraphQL (instead of or alongside REST)**

**What changes**

- **API layer**: REST/JSON → **GraphQL**. Same operations; client specifies exact fields (no over-fetching). Resolvers call the same services/repositories.
- **DB**: Unchanged; same schema, same queries (or slightly optimized per-request shapes).

**Expected performance impact**

- **Network**: Fewer bytes and round trips when client needs a subset of fields or related entities in one request.
- **Server**: Resolver execution and N+1 avoidance (DataLoader, batching) affect latency.

**Effort**: **Medium** – add GraphQL (e.g. Spring for GraphQL), schema, resolvers. Same DB. Switch back: use REST only.

**Switchable**: ✅ Same DB, same services. Toggle by exposing REST only, or GraphQL only, or both.

---

### 3.7. **Stored procedures / DB-side logic**

**What changes**

- **Logic location**: Move part of the logic (validation, multi-step updates, aggregations) into **PostgreSQL functions/procedures**. App calls `CALL` or `SELECT fn(...)` instead of many round trips.
- **DB**: Same tables; new functions. No change to row format.

**Expected performance impact**

- **Round trips**: Fewer; complex flows run in one DB call.
- **Locking and consistency**: More work inside a single transaction.
- **DB CPU**: Higher; app CPU lower.

**Effort**: **Medium** – write functions, migrate Flyway, change repositories to call them. Switch back: re-implement logic in app, remove or bypass functions.

**Switchable**: ✅ Same schema. App can switch between “call procedure” and “many app-side JDBC calls” by changing repository implementation.

---

### 3.8. **Queue-in-front (async request handling)**

**What changes**

- **Flow**: Request → **message queue (e.g. RabbitMQ, Kafka, SQS)** → worker consumes, does the same CRUD, responds via callback or polling. Or: HTTP returns 202 + `Location`; client polls for result.
- **DB**: Same PostgreSQL, same CRUD; worker writes to the same tables. **Not** event sourcing: events are not the source of truth, only a transport.

**Expected performance impact**

- **Throughput**: Better absorption of spikes; queue buffers, workers scale.
- **Latency**: Higher (queue + worker); not suitable when client needs immediate response.
- **Decoupling**: Client and worker can be scaled separately.

**Effort**: **Medium** – add queue, worker, optional callback/polling. Switch back: remove queue, call service directly in the HTTP handler.

**Switchable**: ✅ Same DB, same CRUD. Switching = synchronous HTTP handler that calls the same service.

---

### 3.9. **Actor model (e.g. Akka, Virtual Threads as “actors”)**

**What changes**

- **Concurrency**: Replace “thread-per-request” or pool with **actors**: one actor (or pool) per aggregate or use case; messages instead of direct calls. Actors still use JDBC/R2DBC to the same DB.
- **DB**: Same schema; only the way the app organizes work changes.

**Expected performance impact**

- **Throughput and backpressure**: Mailbox and supervision can smooth load; different contention behavior than a large shared pool.
- **Location**: If actors are distributed (Akka Cluster), network comes in; for single-JVM actors, it’s mainly a different threading/dispatch model.

**Effort**: **High** – Akka (or similar) has a different programming model. Switch back: replace with servlet/controller + service + repository.

**Switchable**: ✅ Same DB, same tables. Switching = reimplement entrypoint and dispatch, keep repositories.

---

### 3.10. **Serverless / FaaS (e.g. AWS Lambda, Azure Functions)**

**What changes**

- **Deployment**: Same JAR or functions; run as **short-lived, per-request or per-batch** invocations instead of a long-lived server. DB connection: via RDS Proxy, PgBouncer, or per-invocation (with pooling constraints).
- **DB**: Same PostgreSQL, same schema. App code can stay almost the same if you use adapters (e.g. Lambda with Spring Cloud Function or Quarkus Funqy).

**Expected performance impact**

- **Cold start**: Higher latency on first request or when scaled to zero.
- **Throughput**: Auto-scaling; good for spiky, parallel workloads.
- **Cost**: Pay per invocation; can be cheaper at low or irregular load.

**Effort**: **Medium** – adapt bootstrap, DB connection, and deployment. Switch back: deploy the same code as a regular JAR on a server or container.

**Switchable**: ✅ Same DB, same logic. Switching = change deployment target (FaaS vs Tomcat/Netty).

#### How to introduce a serverless architecture with the exact same features

To get **the same features** (Person CRUD, Consents CRUD, Snapshot reads, Audit to DB + Kafka, JWT security, health) in a serverless setup while keeping the **same PostgreSQL** and the **same REST API**:

---

**1. Request routing (API Gateway + Lambdas)**

- Put an **API Gateway** (e.g. AWS API Gateway, Azure API Management, or a custom gateway like Kong) in front. It routes by path and method to the right function.
- Map each **route** to a **Lambda (or Azure Function)**:
  - `POST /api/person` → `PersonCreateFunction`
  - `GET /api/person/{personId}`, `PUT /api/person/{personId}`, `DELETE /api/person/{personId}` → `PersonReadFunction`, `PersonUpdateFunction`, `PersonDeleteFunction` (or one `PersonFunction` that branches on method).
  - `POST /api/person/{personId}/consents`, `GET /api/person/{personId}/consents`, `GET /api/person/{personId}/consents/{consentId}`, `PUT`, `DELETE`, `POST .../withdraw` → consent functions.
  - `GET /api/snapshot/person/{personId}/latest`, `GET /api/snapshot/by-email/{email}`, `GET /api/snapshot/by-phone/{phone}`, and optionally `POST /api/snapshot/person/{personId}` (microservices style) → snapshot functions.
  - `GET /api/person/health` (or `/actuator/health`) → `HealthFunction`.
- You can use **one function per resource** (person, consent, snapshot) and branch on HTTP method and path inside the handler, or **one function per operation**; both keep the same API surface. The handler receives the HTTP event, parses path/query/body, and calls the same service/repository logic you use today.

---

**2. Security (JWT)**

- **Option A – Authorizer in the gateway**: Configure a **Lambda (or built-in) authorizer** that validates the JWT (signature, expiry, issuer), extracts claims, and returns an allow/deny plus a context (e.g. `principalId`, `roles`). The gateway forwards that context to the business Lambdas. The business code does not need to change; it just receives an already-validated principal. This matches “validate token JWT” in the same place as today (before the handler).
- **Option B – Inside each function**: Each handler validates the JWT using the same library and public key as the monolith (from env, Parameter Store, or secret). Same behaviour, more duplication and slightly more cold-start cost.
- Reuse the same **JWT config** (public key path, accepted issuers) so token format and validation rules stay identical.

---

**3. Database (same PostgreSQL, same schema)**

- **Same DB**: Point all functions at the **same** PostgreSQL instance (RDS, Aurora, or your self‑managed Postgres). Use the **same schema and Flyway migrations**; no new tables for “serverless” — it’s the same CRUD model.
- **Connections**: Lambdas create new processes; each can open DB connections. PostgreSQL has a limited number of connections, and with many concurrent invocations you can exhaust them. To keep the **same effective behaviour** (many concurrent requests without connection errors):
  - Use **RDS Proxy** (AWS) or **PgBouncer** (or similar) in front of Postgres. Functions connect to the proxy; the proxy keeps a smaller, stable pool to the real DB. Same DDL and DML; only the TCP endpoint changes.
  - Or use a **serverless Postgres** option (e.g. Aurora Serverless v2, Neon, Supabase) that handles connection scaling; you still use standard JDBC/R2DBC and the same SQL.
- **Connection reuse inside a Lambda**: In Java, instantiate a **single** `DataSource` or `JdbcTemplate` (or R2DBC equivalent) in a **static** or **top-level** variable, created on first invocation and reused while the execution environment is warm. That matches the “one pool per process” idea and avoids opening a new connection per request when the container is reused.
- **Transactions**: Use the same transactional boundaries as today (e.g. `@Transactional` or explicit `begin/commit` in the service). The only difference is the physical connection comes from the proxy or from the reused in-process pool.

---

**4. Audit (DB + Kafka)**

- **DB**: In the same transaction (or immediately after) as the business write, insert into the **audit** table(s) using the same `AuditService` / `AuditSchemaWriter` logic. The function has JDBC (or R2DBC) and runs the same SQL. No change to schema or semantics.
- **Kafka**: The function needs a Kafka client. Lambda can:
  - **Sync**: Produce directly to Kafka from inside the Lambda (same code paths as the monolith). You need VPC/security group and Kafka reachable from the Lambda. Same “send Kafka message with the event” behaviour.
  - **Async**: If Kafka is not easily reachable or you want to decouple, write the “to be published” event to an **outbox** table in the same transaction as the business + audit write. A **separate** Lambda (or a Kafka Connect outbox connector, or a small poller) reads the outbox and publishes to Kafka. The observable effect (audit in DB + message in Kafka) is the same; only the wiring changes. The outbox table is an extension of the existing schema, not a new “serverless-only” model.
- Reuse the same **event shape and topics** so downstream consumers see the same payloads.

---

**5. Person, Consents, Snapshot (same services and repositories)**

- **Logic**: The **PersonService**, **ConsentService**, **PersonSnapshotService** (and any repositories: `PersonRepository`, `SimpleConsentMapper`, `SchemaSnapshotWriter`, etc.) stay as they are. They are just **invoked from a function handler** instead of from a Spring MVC controller. The handler: (1) maps the API Gateway event (path, method, body, auth context) into the same DTOs and IDs the controllers use, (2) calls `personService.create(...)`, `consentService.list(...)`, etc., (3) maps the result to an HTTP response (status, JSON body). Same in/out and status codes as the monolith.
- **Dependency injection**: In a pure FaaS style you might wire services manually or with a small DI container (e.g. a lightweight Spring context that only creates `DataSource`, `JdbcTemplate`, repositories, services, `AuditService`, Kafka producer) in the **init**/cold-start path. That context is reused across warm invocations. Alternatively, use **Spring Cloud Function** or **Quarkus Funqy** so your existing `@Service` / `@Repository` beans are reused with minimal adapters; the “same features” remain in the same classes.
- **Snapshot by email/phone**: These need to resolve email/phone to `personId` and then fetch the latest snapshot. Same as today: either a query to your existing tables (or a view) or a call to a shared “resolution” helper. The function just calls that logic; no new behaviour.

---

**6. Optional: Snapshot creation (microservices-style `POST /api/snapshot/person/{personId}`)**

- If you support “create snapshot” via HTTP: a `SnapshotCreateFunction` receives the request, optionally fetches `PersonData` from the person service (in a serverless monolith, that’s an in-process call to `PersonService`; in a split design, an HTTP call to a Person Lambda or the same DB). It then calls the same `PersonSnapshotService` (or equivalent) to store the snapshot and, if applicable, writes to the outbox for Kafka. Same DB, same snapshot shape.

---

**7. Same REST surface (paths, methods, status codes, bodies)**

- API Gateway is configured so that `{proxy+}` or explicit routes preserve path, method, query, and body. The function receives them and returns status and JSON. The **contract** (request/response format, error bodies, `Content-Type`) is unchanged, so the same JMeter plans, integration tests, and clients work. Only the `BASE_URL` (and possibly auth header handling if the authorizer injects it) differs.

---

**8. Summary: what stays the same, what is only wrapped**

| Feature | Same in serverless |
|---------|-------------------|
| **DB** | Same PostgreSQL, same schema, same Flyway, same SQL. |
| **Person CRUD** | Same service/repository; handler adapts HTTP ↔ service call. |
| **Consents CRUD + withdraw** | Same. |
| **Snapshot reads (by personId, email, phone, id)** | Same. |
| **Audit (DB)** | Same `AuditService` / `AuditSchemaWriter`; runs in the same transactional or follow-up flow. |
| **Kafka** | Same event shape and topics; either direct produce from the function or via outbox + worker. |
| **JWT** | Same validation rules and key; in authorizer or in each function. |
| **Health** | Same checks (DB, optionally Kafka); a small `HealthFunction` that runs them. |
| **REST API** | Same paths, methods, request/response formats, status codes. |

The only “new” pieces are: **API Gateway** (routing), **Lambda/Function runtime** (invocation and HTTP adapter), and **connection pooling** (RDS Proxy / PgBouncer or serverless DB). The domain logic, schema, and API stay identical so you can switch back to a monolith by deploying the same services in a Tomcat/Netty process.

#### Running on Azure and locally for testing (all Java)

You can **run on Azure** as the target and **run the same Java functions locally** for testing. All application code (handlers, services, repositories, JDBC, Kafka client, JWT) stays in **Java**; only config and the Functions host change between local and Azure.

**Target: Azure**

- **Runtime:** **Azure Functions** (Java 11 or 17). Your code is a Java JAR; Azure runs it in their Functions host.
- **HTTP:** Use the **HTTP trigger** (`@FunctionName` with `HttpTrigger`) so each request hits your Java handler. For a single REST surface you can use one function with `route = "api/{*path}"` and route internally, or split by resource (person, consent, snapshot). Optionally put **Azure API Management** in front for auth, routing, or rate limits.
- **DB:** **Azure Database for PostgreSQL** (or Flexible Server). Same schema and Flyway. Use **connection pooling** in front (e.g. **PgBouncer** in an App Service or container, or built-in pooling) so many concurrent functions don't exhaust connections. Connection string and credentials in **Application Settings** (or Key Vault references).
- **Kafka:** **Azure Event Hub** with Kafka protocol, or **Confluent Cloud** / self-managed Kafka on Azure. Bootstrap servers and credentials in Application Settings. Same Java Kafka client and event shapes.
- **JWT:** Validate in each function or via API Management policy; same Java `jjwt` (or similar) and public key, from App Settings or Key Vault.
- **Build & deploy:** Maven `azure-functions-maven-plugin` or Gradle equivalent. `mvn azure-functions:deploy` (or CI/CD) produces and deploys the JAR to your Function App.

**Local testing (same Java, no Azure account needed)**

Run the **same Java Functions** on your machine using **Azure Functions Core Tools** and your existing **Postgres + Kafka** (e.g. in `docker-compose`). The Core Tools host behaves like Azure's; only the config points to `localhost`.

1. **Install Azure Functions Core Tools (v4)** — [Install guide](https://learn.microsoft.com/azure/azure-functions/functions-run-local) (Windows: npm/choco; macOS: brew; Linux: npm or package manager). The host process is Node-based, but it only **invokes** your JAR; your entire app (handlers, services, JDBC, Kafka) is **Java**.
2. **Start Postgres and Kafka** — Use your existing `docker-compose` (postgres, kafka). Run: `docker compose up -d`
3. **Config for local: `local.settings.json`** — In the function project root, set `Values` such as: `FUNCTIONS_WORKER_RUNTIME=java`, `SqlConnectionString=jdbc:postgresql://localhost:5432/pdms`, `DbUser`, `DbPassword`, `KafkaBootstrapServers=localhost:9092`, `JwtPublicKeyPath=./jwt-keys/public-key.pem`. Your Java code reads these via `System.getenv(...)` or the Functions config helpers. Same env names as on Azure; only the values (localhost) differ.
4. **Run the Functions host locally** — From the function project: `mvn clean package` then `func start`, or `mvn azure-functions:run`. The host loads your JAR and serves HTTP at e.g. `http://localhost:7071/api/...`. Your Java handlers run in the JVM; Postgres and Kafka at `localhost`.
5. **Hit the same REST API** — `GET http://localhost:7071/api/person/{personId}`, `POST http://localhost:7071/api/person`, etc. Use your JMeter plan or tests with `BASE_URL=http://localhost:7071`.
6. **Optional: Azurite** — Only if you use Azure Blob/Queue/Table: run **Azurite** and set `AzureWebJobsStorage`. For PDMS with only Postgres + Kafka, leave `UseDevelopmentStorage=true` or a dummy value.

**What is "all Java"**

| Layer | Technology | Java? |
|-------|------------|-------|
| Function handlers, services, repos, JDBC, Kafka, JWT | Your code | Yes |
| Build | Maven / Gradle | Yes |
| Runtime for your JAR | Azure Functions (Java worker) | Yes |
| Local runner | Azure Functions Core Tools | Host is Node; it only starts the Java worker and proxies HTTP. Your logic is all Java. |

**Config: local vs Azure**

| Setting | Local (`local.settings.json`) | Azure (Application Settings) |
|---------|------------------------------|-----------------------------|
| DB | `jdbc:postgresql://localhost:5432/pdms` | `jdbc:postgresql://<azure-postgres>.postgres.database.azure.com:5432/pdms` |
| Kafka | `localhost:9092` | Event Hub Kafka endpoint or Confluent bootstrap list |
| JWT public key | File path or embedded for dev | Key Vault reference or App Setting |

Use the **same env/keys** in code; only the values differ. One codebase for local and Azure.

**Minimal local workflow**

1. `docker compose up -d` (Postgres, Kafka)
2. `local.settings.json` with `localhost` URLs and JWT key path
3. `mvn azure-functions:run` or `func start` in the function project
4. Tests and JMeter against `http://localhost:7071/api/...`

Same JAR can be deployed to Azure; there you switch to Azure Postgres, Event Hub/Confluent, and Key Vault/App Settings. No Azure account or deployment is required for local testing.

---

### 3.11. **BFF (Backend for Frontend)**

**What changes**

- **Layer**: A **BFF** (per client type, e.g. web, mobile) sits in front of the same backend. BFF aggregates and shapes data; it calls existing REST/GraphQL/gRPC and writes to the same DB only if it owns some logic. Often BFF is read-only and delegates to the main app.
- **DB**: For PDMS, BFF typically calls the monolith; DB stays shared and unchanged. If BFF has its own DB access, it uses the same schema.

**Expected performance impact**

- **Round trips**: Fewer from client to server (one BFF call instead of many).
- **Payload**: Tailored; less over-fetching. Slightly more work on the BFF.

**Effort**: **Medium** – new BFF service, routes to existing APIs. Switch back: clients call the main app directly; remove or bypass BFF.

**Switchable**: ✅ Same DB and backend. Switching = point clients at the main app.

---

### 3.12. **Pipeline / middleware chain**

**What changes**

- **Request path**: Request goes through a **pipeline** (e.g. validation → auth → rate-limit → business handler → audit → persist). Stages can be sync or async; persistence at the end is the same CRUD.
- **DB**: Same schema; only the orchestration of steps changes.

**Expected performance impact**

- **Overhead**: Per-stage dispatch and context passing; can add filters (logging, metrics) with small cost.
- **Flexibility**: Easier to add/remove/reorder cross‑cutting logic; can run some stages in parallel.

**Effort**: **Low–Medium** – often already partially in place (filters, interceptors). Making it explicit and configurable is the main work. Switch back: inline pipeline into a single handler.

**Switchable**: ✅ Same DB, same final persistence. Switching = flatten or rewire the pipeline.

---

### 3.13. **Read replicas (app directs reads to replica)**

**What changes**

- **DB topology**: Add a **read replica**; app (or a config flag) sends **read** queries to the replica and **writes** to the primary. Same schema on both.
- **App**: DataSource routing (e.g. Spring `AbstractRoutingDataSource`, or separate `JdbcTemplate` for reads). No change to table structure.

**Expected performance impact**

- **Read capacity**: Offload read traffic from the primary; better read throughput and lower primary load.
- **Replication lag**: Reads may be slightly stale; acceptable for many PDMS read flows.

**Effort**: **Medium** – replica setup, routing in the app. Switch back: route all traffic to the primary.

**Switchable**: ✅ Same schema. Switching = point reads back to the primary.

---

### 3.14. **HTTP / edge caching**

**What changes**

- **Layer**: **HTTP caching** (e.g. `Cache-Control`, `ETag`, `If-None-Match`) and/or **edge cache (CDN, reverse proxy)** in front of the app. GETs for person, consents, snapshot can be cached where permitted.
- **DB**: Unchanged; cache is in front. App logic unchanged; some endpoints must send correct headers or respond to `If-None-Match`.

**Expected performance impact**

- **Read latency and load**: Large improvement for cacheable, repeated reads; less DB and app load.
- **Writes**: Unchanged or invalidate on write.

**Effort**: **Low–Medium** – headers, validation, optional Varnish/nginx/CDN. Switch back: disable or bypass cache.

**Switchable**: ✅ Same DB, same app. Switching = turn off cache or remove edge layer.

---

### Excluded: Event Sourcing

**Event Sourcing** is **not switchable** in the sense used here: the **source of truth** becomes the **event store**. To “switch back” to a CRUD-based architecture you would need a one-time migration to rebuild current state into normalized tables. The data model is no longer the same as the other variants. It is therefore **excluded** from this benchmark set.

---

## 4. Summary: same DB, switchable, what changes, and effort

| Architecture | Same DB | Switchable | What changes (runtime) | Performance levers | Effort |
|--------------|---------|------------|------------------------|--------------------|--------|
| **Virtual Threads** | ✅ | ✅ | Each request runs on a virtual thread instead of a platform thread; Servlet and JDBC stay the same. DB connection pool size can be raised. | Higher throughput under high concurrency; lower memory per request; fewer thread-pool queuing effects. | Low |
| **Reactive (WebFlux + R2DBC)** | ✅ | ✅ | Tomcat and JDBC are replaced by Netty and R2DBC; controllers and repos return `Mono`/`Flux`. Same schema and tables. | Non-blocking I/O reduces threads; can improve throughput when I/O-bound; backpressure helps under load. | High |
| **Cache-aside (Caffeine/Redis)** | ✅ | ✅ | Reads hit an in-process or external cache first; on miss, load from PostgreSQL and fill cache. Writes invalidate or write through. DB remains source of truth. | Much lower read latency and higher read throughput for repeated access; less DB load on read-heavy workloads. | Medium |
| **gRPC** | ✅ | ✅ | REST/JSON is replaced by gRPC and Protocol Buffers; same operations and services, different transport and serialization. | Smaller payloads, faster serialization; HTTP/2 and multiplexing cut round trips; often 20–40% better latency/throughput. | Medium |
| **CQRS + materialized read** | ✅ | ✅ | Reads are served from denormalized read tables or views, updated from the write model; writes stay in normalized tables. Same DB instance. | Simpler, faster read queries with fewer JOINs; better read throughput and latency for complex person/consent/snapshot responses. | Medium–High |
| **GraphQL** | ✅ | ✅ | REST is replaced or complemented by GraphQL; client requests only the fields it needs. Resolvers call the same services and DB. | Less over-fetching and fewer client round trips; server cost depends on resolvers and N+1 handling (e.g. DataLoader). | Medium |
| **Stored procedures** | ✅ | ✅ | Part of the business logic moves into PostgreSQL functions; the app calls them instead of many JDBC round trips. Tables and schema stay the same. | Fewer round trips; more work in a single transaction; DB CPU goes up, app CPU goes down. | Medium |
| **Queue-in-front** | ✅ | ✅ | HTTP requests go to a queue; a worker consumes and performs the same CRUD against PostgreSQL. Optional 202 + polling or callbacks. Not event sourcing. | Queues absorb traffic spikes; workers scale independently; latency rises due to queuing and async handling. | Medium |
| **Actor model (Akka)** | ✅ | ✅ | Concurrency is organized around actors and mailboxes instead of a thread pool; actors still use JDBC/R2DBC against the same schema. | Different contention and backpressure; can improve throughput when load is highly concurrent. | High |
| **Serverless / FaaS** | ✅ | ✅ | The same app or handlers run as short-lived functions (e.g. Lambda); they talk to the same PostgreSQL, often via a proxy or pool. | Cold starts add latency; scale-to-zero and per-invocation scaling can improve throughput and cost for spiky or irregular load. | Medium |
| **BFF** | ✅ | ✅ | A Backend for Frontend sits in front of the main app; it aggregates and shapes data from existing APIs. The main app and DB are unchanged. | Fewer client round trips and tailored payloads; slightly more work and latency in the BFF. | Medium |
| **Pipeline / middleware chain** | ✅ | ✅ | Requests pass through an explicit pipeline (e.g. auth, validation, rate-limit, handler, audit); persistence is the same CRUD at the end. | Small per-stage overhead; easier to add or reorder cross-cutting behaviour and run stages in parallel. | Low–Medium |
| **Read replicas** | ✅ | ✅ | A read replica is added; the app routes read queries to the replica and writes to the primary. Same schema on both. | Higher read capacity and throughput; lower load on the primary; reads may be slightly stale (replication lag). | Medium |
| **HTTP / edge caching** | ✅ | ✅ | HTTP caching (`Cache-Control`, `ETag`, `If-None-Match`) and/or an edge cache (CDN, Varnish) sit in front of the app. DB and app logic are unchanged. | Large gains in read latency and throughput for cacheable GETs; less load on the app and DB. | Low–Medium |

---

## 5. Benchmarking: how to integrate

All of these can keep the **same HTTP REST API** (except gRPC, which needs a gRPC client). For REST variants:

- **JMeter**: Same benchmark plans and scenarios; collected as a single `jmeter.zip` artifact per run.
- **Prometheus / Grafana**: Collected per selected time window through the Prometheus VM collector.
- **Host VM CPU/RAM**: Collected per VM in `hostMetrics/` (uses `sysstat`/`sar` when available).
- **Run discipline**: Same data workload profile per repetition; only swap variant and keep scenario/range consistent.

Suggested **run matrix**:

1. **Baseline**: `pdms-monolith` (blocking, no cache).
2. **Virtual Threads**: `pdms-monolith` with `spring.threads.virtual.enabled=true` (and possibly larger Hikari pool).
3. **Reactive**: `pdms-monolith-reactive` (or modular-reactive).
4. **Cache**: `pdms-monolith-cache` (or modular-cache).
5. **gRPC**: `pdms-monolith-grpc` with a gRPC JMeter plugin or custom client.
6. **GraphQL**, **stored procedures**, **queue-in-front**, **read replicas**, **HTTP caching**: Same JMeter (or adapted) against the REST API; swap the app variant. **CQRS + materialized read** and **BFF** similarly.

---

## 6. Suggested implementation order for benchmarking

1. **Virtual Threads** – Easiest: config change. Immediate signal on concurrency.
2. **HTTP / edge caching** – Low effort; `Cache-Control` / `ETag`; big impact on repeated reads.
3. **Cache-aside (Caffeine)** – Moderate effort; in-process cache for read-heavy scenarios.
4. **Pipeline / middleware chain** – Low–medium; often partly in place; explicit pipeline to compare overhead.
5. **Read replicas** – Medium; route reads to replica; compare primary vs replica read capacity.
6. **gRPC** – Medium; REST vs gRPC on same DB; needs gRPC client in JMeter.
7. **GraphQL** – Medium; REST vs GraphQL; over-fetch and round trips.
8. **Stored procedures** – Medium; move some logic into DB; round trips and DB CPU.
9. **Queue-in-front** – Medium; async request handling; throughput vs latency.
10. **CQRS + materialized read** – Medium–high; extends existing CQRS; read query cost.
11. **BFF** – Medium; extra layer; client round trips.
12. **Serverless / FaaS** – Medium; deploy same app as functions; cold start and scaling.
13. **Reactive (WebFlux + R2DBC)** – High; different execution model.
14. **Actor model (Akka)** – High; different concurrency model.

**Excluded**: Event Sourcing — not switchable (source of truth becomes event store; cannot switch back without a migration).

---

## 7. Optional: DB and connection-pool dimensions (same architecture)

Even with “same architecture,” you can benchmark **configuration** variants on the same DB:

- **HikariCP `maximum-pool-size`**: e.g. 20 vs 80 vs 200 (especially meaningful with virtual threads).
  *Note:* the benchmarked campaign fixes this at a total of 200 per architecture against a
  PostgreSQL `max_connections=250` (see §0.3). Any pool-sizing experiment must stay within that
  server limit — exceeding it produced `FATAL: sorry, too many clients already` and broke
  `pg_dump` during collection.
- **Prepared statement cache**, **fetch size**.
- **Read replica** (if you add one): direct reads from primary vs replica—same app, different DB target. This is a DB topology change; only include if you still consider it “same logical data.”

These can be documented as “Tuning” or “Configuration” variants rather than new architectures, but they are easy to plug into the same JMeter + Prometheus setup.

---

## 8. Relation to `ARCHITECTURE_COMPARISON.md`

- **ARCHITECTURE_COMPARISON.md**: Feature parity, endpoints, and data formats across **deployment and processing models** (mono, modular, micro, sync/async).
- **This document**: **Runtime/execution architectures** that keep the same (shared) DB and are chosen to **impact performance** in a measurable way for benchmarking.

Together they define:

- **What** each variant does (features, API, DB topology).
- **Why** each variant is interesting for performance (threading, I/O, caching, protocol, write/read model).

---

## 9. Next steps

1. **Decide** which of the above to implement first (recommendation: Virtual Threads, then Cache).
2. **Add** a short “Benchmark variants” section to `ARCHITECTURE_COMPARISON.md` that points to this document and lists which modules (or profiles) correspond to which runtime architecture.
3. **Extend** JMeter and/or run scripts to:
   - switch `BASE_URL`/port by variant, and  
   - for gRPC, add a gRPC test plan or script.
4. **Record** in `ARCHITECTURE_COMPARISON.md` or a separate `BENCHMARK_RESULTS.md` which architectures use the **shared DB** (all in this document) vs **database-per-service** (current microservices).



1) Set JMeter memory (most important)
   Create/edit ~/jmeter/bin/setenv.sh:

#!/usr/bin/env bash
HEAP="-Xms1g -Xmx2g -XX:MaxMetaspaceSize=256m"
Then:

chmod +x ~/jmeter/bin/setenv.sh
JMeter startup scripts load this automatically.