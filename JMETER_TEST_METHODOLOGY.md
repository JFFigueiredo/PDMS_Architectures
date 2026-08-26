# PDMS – JMeter Request Types per Architecture

This document records **what was actually run with JMeter, against which architecture, and what each request mix consisted of**. It is derived directly from the `.jmx` test plans and `run-*.sh` runner scripts in this repository, so it can be used as the "Experimental Setup" / "Workload" section source material for papers.

It complements `ARCHITECTURES_FOR_BENCHMARK.md` (which describes *why* each architecture is interesting) and the Thesis Plan (Chapter 3, quasi-experimental design comparing architectural patterns on standardized workloads).

Scope note: the `run-*-fast.sh` scripts (`run-read-heavy-fast.sh`, `run-write-heavy-fast.sh`, `run-step-load-fast.sh`) are ~35s smoke-test variants used only to verify a script works before committing to a full run — **no benchmark data was collected with them**. Everything below refers to the full-length runners only.

---

## 1. Architectures under test

Defined in `scripts/data_collector/restart_all_services.sh`, all pointed at the **same shared PostgreSQL/Kafka infrastructure** (see `ARCHITECTURES_FOR_BENCHMARK.md` §1):

| Variant flag | App module | Notes |
|---|---|---|
| `monolith` | `pdms-monolith` | Single deployable, blocking I/O, single port (8080) |
| `microservices` | Person/Consent/Snapshot services (sync) | 3 separate ports: person `8080`, snapshot `8081`, consent `8082` |
| `microservices-async` | Person/Consent/Snapshot services (async, `*-async` modules) | Same 3-port split and the **same HTTP call graph** as `microservices` — see the note below |
| `faas` | `pdms-faas` | Function-style handlers, same shared DB/Kafka infra |
| `decentralized` | `pdms-decentralized` | Same shared DB/Kafka infra + Besu blockchain node **on its own dedicated VM** (`<besu-host>`, since 2026-08) |

All variants expose the **same REST contract** (`/api/person`, `/api/person/{id}/consents`, `/api/snapshot/...`), which is what makes a single set of JMeter plans reusable across all five benchmarked variants.

> **What `microservices-async` actually is.** It is **not** a message-driven variant. It runs the
> **same three-service HTTP call graph** as `microservices`; the sole difference is that the
> snapshot write is issued as `.subscribe(...)` instead of `.block()` — fire-and-forget rather than
> waiting for the response. **There are no Kafka consumers anywhere in the codebase**:
> `grep -rn "@KafkaListener"` across every service tree returns **zero**. Kafka carries
> publish-only outbox/audit traffic, and it does so **identically in all five variants**. Do not
> describe this variant as "Kafka between services", "async consumers" or "messaging overhead" —
> the observable difference is unobserved write completion on one HTTP hop, which is also why it is
> the only variant that returns errors (reads that race the not-yet-written snapshot).

**Benchmarked subset:** the final campaign collected **five** variants and only five —
`monolith`, `faas`, `microservices`, `microservices-async`, `decentralized`. Do not report more
than five architectures from this dataset.

---

## 2. JMeter test plans actually used (`jmeter/scripts/`)

Four scenarios were driven by dedicated runner scripts. All four hit the same REST surface; only the **request mix and ratio** differ.

### 2.1 Mixed / Step-Load — `PDMS-Combined-Benchmark.jmx` (via `run-step-load.sh`)

**Campaign runs use a single enabled thread group.** In `PDMS-Combined-Benchmark.jmx`
and `PDMS-Combined-Benchmark-local.jmx`, the
following groups are present but `enabled="false"`:

- `Person Operations - CRUD`
- `Consents Operations - CRUD`
- `Snapshot Operations`
- `GDPR Isolated - G1 Create Person with Consent`
- `GDPR Isolated - G2 Consent Lifecycle`
- `GDPR Isolated - G3 Snapshot Retrieval`

Only **`Mixed Benchmark - Real Scenario`** runs during the architecture-comparison campaign
(`enabled="true"` in the `-local` plan; attribute omitted — JMeter default enabled — in
`PDMS-Combined-Benchmark.jmx`). Collected `.jtl` files show that one named group and the sequential
mixed lifecycle samplers (no G1/G2/G3 labels). Confirmed against the `.jmx` 2026-08-05 (G1/G2/G3 groups disabled in campaign plans).

**Mixed "real scenario" sequence** (one synthetic end-to-end user session per iteration):
Person Create → Update → Get, Consent Create → List, Snapshot reads (all 3 lookups),
Consent Withdraw → Delete, Person Delete.

G1/G2/G3 thread groups exist in the `.jmx` but were **disabled** during the campaign (`run-step-load.sh`);
no isolated GDPR runs were collected.

This is the plan used to find the **rupture point** (throughput/latency vs. concurrency) of each architecture.

### 2.2 Read-Heavy — `PDMS-Read-Heavy-Benchmark.jmx` (via `run-read-heavy.sh`)

Ratio: **20% writes / 80% reads**. Each iteration self-seeds (writes first, then reads the same record) so no external pre-population is required:

```
POST /api/person                              (Person - Create)
POST /api/person/{id}/consents                (Consent - Create)
GET  /api/person/{id}                         (Person - Get)
GET  /api/person/{id}/consents                (Consents - List)
GET  /api/snapshot/person/{id}/latest         (Snapshot - Get by Person ID)
GET  /api/snapshot/by-email/{email}           (Snapshot - Get by Email)
GET  /api/snapshot/by-phone/{phone}           (Snapshot - Get by Phone)
GET  /api/person/{id}                         (Person - Get (repeat))
GET  /api/person/{id}/consents                (Consents - List (repeat))
GET  /api/snapshot/person/{id}/latest         (Snapshot - Get by Person ID (repeat))
```
→ 10 samplers: 2 writes + 8 reads per iteration. **Three** samplers carry a `(repeat)` label —
`Person - Get`, `Consents - List` and `Snapshot - Get by Person ID` — and those labels are part of
the sampler names in the `.jtl`, so they appear as separate rows in `endpoints.csv`.

**Coverage:** read-heavy contains **no PUT and no DELETE**. Person update and Art. 17 erasure are
therefore not exercised by this scenario.

### 2.3 Write-Heavy — `PDMS-Write-Heavy-Benchmark.jmx` (via `run-write-heavy.sh`)

Ratio: **80% writes / 20% reads**:

```
POST /api/person                                   (Person - Create)
PUT  /api/person/{id}                               (Person - Update)
POST /api/person/{id}/consents  ×3                  (Consent - Create A/B/C)
POST /api/person/{id}/consents/{cid}/withdraw ×3    (Consent - Withdraw A/B/C)
GET  /api/person/{id}                               (Person - Get)
GET  /api/person/{id}/consents                      (Consents - List)
```
→ 10 samplers: 8 writes + 2 reads per iteration.

**Coverage:** write-heavy contains **no snapshot samplers at all** and **no DELETE**. It never reads
a snapshot back, which is why it records a 0.00% error rate even for `microservices-async` — the
race that produces that variant's 404s cannot be observed here. Art. 17 erasure is likewise absent.

### Scenario coverage at a glance

| | POST | PUT | DELETE | Snapshot GET | Samplers/iteration |
|---|---|---|---|---|---|
| Mixed | ✅ | ✅ | ✅ (consent + person) | ✅ (all 3 lookups) | **11** |
| Read-heavy | ✅ | ❌ | ❌ | ✅ (4 calls, 1 repeated) | **10** |
| Write-heavy | ✅ | ✅ | ❌ | ❌ **none** | **10** |

**Art. 17 (right to erasure) is exercised in the mixed plan only.** Any erasure-cost claim must be
sourced from `results/*/mix/`, never from read- or write-heavy. Likewise, snapshot-read cost exists
only in mixed and read-heavy.

### 2.4 GDPR-Isolated groups (disabled — not in dataset)

The Combined plan still defines G1/G2/G3 thread groups for isolated GDPR operations, but they were
**disabled** in every campaign run and **no `.jtl` data** exists for them:

| Group | Operation sequence | What it would isolate |
|---|---|---|
| **G1** | `POST /api/person` (with consent) → `DELETE /api/person/{id}` (cleanup) | Person creation + consent attachment + audit trail write overhead |
| **G2** | `POST .../consents` → `POST .../consents/{id}/withdraw` → `DELETE .../consents/{id}` | Consent create → withdraw → delete round-trip latency |
| **G3** | `GET /snapshot/person/{id}/latest`, `GET /snapshot/by-email/{email}`, `GET /snapshot/by-phone/{phone}` | Audit-trail read performance (snapshot queries) |

Do not cite per-operation GDPR-isolation numbers from this dataset.

### Load profile (common to all four scenarios)

- **Warm-up**: 5 threads / 120s, discarded, before every run, followed by a 30s settle.
- **Steps**: concurrency ladder **`2 → 5 → 15 → 50`** threads (override via `STEPS` env var).
  *Changed 2026-08-13 from `10 → 20 → 40 → 60`; **no campaign data exists on the old ladder** — every
  one of the 75 archives contains only `t2/t5/t15/t50` files.* The old ladder began **at or above
  saturation**, so all four of its steps measured throughput ceilings and none measured per-request
  cost. On the new ladder the **primary** application container runs at **56.9% (monolith) / 62.8%
  (faas) / 56.1% (decentralized) / 69.7% (microservices-person) / 88.1% (microservices-async-person)**
  of its own CPU quota at 2 threads, and ≥91.8% from 5 threads upward (mix scenario, mean over reps
  1–5, window = step start + 40 s to + 300 s — full table in `ARCHITECTURES_FOR_BENCHMARK.md` §0.3).

- **Which rungs are actually unsaturated — and for which variants.** The 2-thread rung is
  unsaturated **only for the three single-deployable variants** (`monolith`, `faas`,
  `decentralized`), which reach just 60–67% of their own 50-thread throughput there. For the two
  three-service variants it is not a low-load regime at all: at 2 threads `microservices` is already
  at **70.4%** (mix) / **83.0%** (read) of its 50-thread throughput, and `microservices-async` at
  **89.0%** (mix) — and in read-heavy it is *faster* at 2 threads than at 50 (**384.6 vs 343.9 rps**).
  **No higher rung recovers the measurement either.** Summed across their three containers, both
  three-service variants plateau at **~72–74% of the 1.8 CPU budget** from 5 threads upward and never
  move, because `person` is pinned at ~100% of its 1.0 quota while ~0.8 CPU sits idle in `snapshot`
  and `consent`. The single-deployables convert the same budget into 92–100%. **Per-request CPU cost
  is therefore not recoverable for either three-service variant at any rung** — what such a figure
  measures is the stranded fixed split, not the intrinsic cost of the architecture.

- **Per-request cost — use with the caveats.** On the doc's published recipe the 2-thread figures are
  **4.34 CPU-ms/request for `monolith` versus 5.89 for `microservices`** (≈1.36×). Both reproduce
  exactly, but the recipe is **internally inconsistent**: it averages CPU over the trimmed
  `[+40 s, +300 s]` window (260 s) while dividing by a request count taken over the full 300 s step,
  which includes the slower ramp — inflating every figure by ~3–5%. Recomputed window-consistently
  (CPU *and* requests both over `[+40 s, +300 s)`, duration 260 s) the pair becomes **4.21 vs 5.60**,
  ratio **1.33×** — the conclusion survives, the digits do not. Two further limits: the
  `decentralized` figure **excludes Besu entirely** (no windowed CPU series exists for the chain
  node, which runs on a fifth VM), and the `microservices-async` figure counts its 2.1–2.5% failed
  requests as requests.
  *(Note the two trims are different things: the CPU analysis window discards the first 40 s, while
  the JMeter ramp itself is 30 s — see the step-duration bullet below.)*

- Steps 5/15/50 remain saturated by design, to show behaviour under load.
- **Step duration**: 300s each (`STEP_DURATION` → `-Jduration`). **The 30s ramp-up (`RAMP=30`) runs
  *inside* that 300s, not before it** — the thread group sets `ThreadGroup.scheduler=true`,
  `duration=${__P(duration,300)}` and `ramp_time=${__P(ramp,30)}`, and in JMeter the scheduler
  duration is the *total* wall-clock life of the group. A step is therefore **300s end-to-end**,
  of which the first 30s is ramp and the **analysed steady state is 270s, not 300s**.
  Two independent confirmations: measured `.jtl` spans are **298.2–298.5s**, and Σ sampler counts
  ÷ throughput gives **268.4s**. Do not write "300s + 30s ramp" or compute per-step totals from
  330s.
- **Cooldown**: 30s between steps.
- **Artifacts**: 5 `.jtl` files per run — 1 warm-up + 4 steps.
- Same JVM heap for JMeter itself: `-Xms512m -Xmx2g`.
- Health-checked via `/actuator/health` before starting; for `microservices`/`microservices-async`,
  all three service ports (8080/8081/8082) are checked independently. Cold-start for three JVMs is
  **48–53s**, so the harness polls for readiness rather than using a fixed settle.

---

## 3. Test plans that exist but were **not** part of the automated runs

Present in `jmeter/scripts/` but not referenced by any `run-*.sh` script — earlier/manual single-domain plans, superseded by the combined/read-heavy/write-heavy plans above:

- `PersonOperations.jmx` — Person CRUD only (Create → Get → Update → Delete)
- `ConsentsOperations.jmx` — Consent CRUD + withdraw lifecycle only
- `SnapshotOperations.jmx` — Snapshot lookups only (by person ID / email / phone)
- `PDMS-Combined-Benchmark-local.jmx` — identical structure to the Combined Benchmark, pointed at local dev config

These should not be cited as part of the benchmark dataset unless you have `.jtl` results for them specifically.

---

## 4b. Execution harness and capture hygiene

Runs are no longer launched by hand. `PDMS/scripts/data_collector/auto_collect.sh <arch> [scenario] [rep]`
(from a deposit root where `data/` and `PDMS/` are siblings — see `DEPLOYMENT_GUIDE.md` §12)
finds missing run folders and, for each, performs 6 steps: restart services → wait for health →
run the JMeter scenario on the load VM → collect artifacts → validate → copy into `data/`. It stops
on the first failure rather than continuing with a damaged run
(see `scripts/data_collector/AUTO_COLLECT.md`).

Guarantees relevant to methodology:

- **Per-repetition isolation.** App and DB/Kafka stacks are recreated with `down -v`; for
  `decentralized` the Besu container is force-recreated (chain data lives in the container layer).
  Every repetition therefore starts from an empty database and, where applicable, an empty chain.
- **Cumulative results directory.** The load VM's `jmeter/scripts/results/` **accumulates every
  historical `.jtl`**. The harness clears it before each run and aborts if the collected archive
  does not contain exactly the expected number of fresh `.jtl` files. **Verified in the script**:
  `EXPECTED_JTL = |STEPS| + 1` (`auto_collect.sh:40`); the directory is emptied in the pre-flight
  (`rm -f ~/jmeter/scripts/results/*.jtl`, `auto_collect.sh:156`); the run `die`s before collection
  if the fresh count differs (`auto_collect.sh:326-328`); and the collected `jmeter.zip` is
  re-counted and must hold exactly `EXPECTED_JTL` and a `WARMUP` file (`auto_collect.sh:212`).
  This previously produced an
  archive holding **182 `.jtl` files** from multiple days; historical output was moved to
  `~/jmeter/results_legacy_*`.
- **Stale JMeter processes.** Orphaned runner scripts on the load VM survive a killed local
  campaign and contaminate the next run. The harness kills runner scripts *first*, then the JVMs
  (that order prevents the script relaunching a step), and aborts if any survive.
- **Postgres connection drain.** Connections are released before collection; without this,
  `pg_dump` failed with `FATAL: sorry, too many clients already`.
- **Health gate.** Polls the service port(s) rather than sleeping a fixed interval — three
  microservice JVMs take 48–53s to become ready.

Optional analysis helpers: `scripts/extract_latest_run.py` and
`compare_reps.py --jtl-session`.

Note that `compare_reps.py` reads `http_status_2xx/4xx/5xx` JSON files; an earlier version read
only the 2xx file and therefore reported error-free runs that in fact had 4xx/5xx traffic. Confirm
you are on the fixed version before quoting error rates.

---

## 5. Data collected (final campaign, 2026-08-14)

The `RetriviedData/` description previously in this section is obsolete. The authoritative dataset
is `data/<architecture>/<scenario>/<rep>/`.

| Architecture | mix | read | writte | Files/rep |
|---|---|---|---|---|
| `monolith` | 5/5 | 5/5 | 5/5 | 177 |
| `faas` | 5/5 | 5/5 | 5/5 | 177 |
| `microservices` | 5/5 | 5/5 | 5/5 | 177 |
| `microservices-async` | 5/5 | 5/5 | 5/5 | 180 |
| `decentralized` | 5/5 | 5/5 | 5/5 | **205** |

Re-counted 2026-08-15 with `find data/<arch>/<scenario>/<rep> -type f`: the three counts hold for
**every** one of the 75 repetitions, with no rep deviating. Campaign total is therefore
**45 × 177 + 15 × 180 + 15 × 205 = 13,740 files**. (Do not quote 19,746 / 20,498 / 21,342 — those
figures appear in older drafts and are wrong.)

**75 repetitions**, 5 architectures × 3 scenarios × 5 reps — **complete as of 2026-08-14**. `decentralized` carries 205 files
because it additionally collects a Besu host-metrics bundle (8 files) and `besu/` chain artifacts
(20 files). `microservices-async` has 180 because it is the only architecture for which the
collector emits the three `kafka_pdms_*_messages.jsonl` files.

The G1/G2/G3 groups (§2.4) were **not** part of this campaign — no reps were collected for them.
Do not write "N architectures × 4 scenarios" into a paper on the basis of this dataset.

Scenario/architecture directory names retain the historical typos the analysis pipeline expects:
`writte` (not `write`) and, in older tooling, `miscroservices`. Verify which spelling a script
expects before pointing it at `data/`.

Per-repetition validation (file count, 5 `.jtl` incl. warm-up, Kafka topic inventory, `General`
4-row summary, `AuditService` exception count, and for `decentralized` the Besu bundle and
artifacts) is recorded in `data/validation_report.md`, Addendum 6.

---

## 6. Quick reference table

| Scenario | JMX | Write:Read ratio | Runner | Steps | Applies to |
|---|---|---|---|---|---|
| Mixed / Step-Load | `PDMS-Combined-Benchmark.jmx` | mixed session only (G1/G2/G3 + CRUD groups **disabled**); measured ≈**55% writes / 45% reads** | `run-step-load.sh` | 2/5/15/50 × 300s | campaign: 5 benchmarked variants (see portfolio constraints) |
| Read-Heavy | `PDMS-Read-Heavy-Benchmark.jmx` | 20:80 | `run-read-heavy.sh` | 2/5/15/50 × 300s | 5 benchmarked variants |
| Write-Heavy | `PDMS-Write-Heavy-Benchmark.jmx` | 80:20 | `run-write-heavy.sh` | 2/5/15/50 × 300s | 5 benchmarked variants |
