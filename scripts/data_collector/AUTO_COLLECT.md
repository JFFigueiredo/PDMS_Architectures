# `auto_collect.sh` — automated gap-filling for benchmark runs

Fills in missing benchmark runs for one architecture, unattended. You give it an
architecture; it finds every empty rep folder under `data/<arch>/{mix,read,writte}/{1..5}`,
runs the full collection cycle for each, validates the result, and stops at the first
problem.

```bash
./auto_collect.sh <architecture> [scenario] [rep|range]
```

| Example | Effect |
|---|---|
| `./auto_collect.sh microservices-async` | every gap, in order mix → read → writte |
| `./auto_collect.sh microservices-async mix` | only the `mix` scenario |
| `./auto_collect.sh microservices-async mix 1` | only `mix` rep 1 (a single run — good for testing) |
| `./auto_collect.sh faas read 2-4` | `read` reps 2, 3 and 4 |

Environment overrides:

| Variable | Default | Purpose |
|---|---|---|
| `KEYS_DIR` | `$HOME/.ssh/pdms-keys` | Folder holding PEM files for SSH (see `DEPLOYMENT_GUIDE.md` §12.2) |
| `BASE_URL` | `http://<app-host>` | target app URL passed to the JMeter runner |
| `JMETER_SSH` | `jmeter_sec` | ssh destination for the JMeter VM (`~/.ssh/config` alias) |
| `STEPS` | `2 5 15 50` | concurrency ladder, passed through to the runner (**changed 2026-08-13** from `10 20 40 60`) |
| `STEP_DURATION` | `300` | seconds per step |
| `HEALTH_TIMEOUT` | `300` | max seconds to wait for the app to become healthy |
| `JMETER_HOME` | `/opt/jmeter` | exported onto `PATH` in the remote command — `~/.bashrc` returns early for non-interactive shells, so JMeter is otherwise not found (exit 127) |
| `DB_SSH` | `dbandkafka_sec` | DB host, used to drain Postgres connections before collection |

Run it from **`PDMS/scripts/data_collector/`** at the **deposit root** (where `data/` and
`PDMS/` are siblings). `collect_all_remote_data.sh` writes its output relative to the
current directory.

---

## What it does per gap

| Step | Action | Stops the script if |
|---|---|---|
| 1 | `restart_all_services.sh <arch>` | exits non-zero |
| 2 | **poll the service port(s) until healthy** (8080, plus 8081/8082 for microservices) | the app is not healthy within `HEALTH_TIMEOUT` |
| 3 | ssh to the JMeter VM, `cd ~/jmeter`, run the scenario's script | ssh exits non-zero, or the output never says `All 4 steps complete.` |
| 4 | `collect_all_remote_data.sh <arch><rep><scenario> <arch>` | exits non-zero |
| 5 | write the captured JMeter output as `General` inside the staging folder | — |
| 6 | validate the staging folder | any check fails |
| 7 | copy staging → `data/<arch>/<scenario>/<rep>/` (staging is kept) | copy fails |
| 8 | re-validate the destination | any check fails |

Scenario → runner mapping:

| Scenario folder | JMeter script |
|---|---|
| `mix` | `run-step-load.sh` |
| `read` | `run-read-heavy.sh` |
| `writte` | `run-write-heavy.sh` |

Staging folders are named `<arch><rep><scenario>` — `faas1mix`, `microservices-async2read` —
matching the convention already used in `scripts/data_collector/`.

### The `General` file

Written by the script, not by hand. The first line reproduces the shell prompt exactly as in
the existing `General` files, followed by the complete captured run output:

```
<ssh-user>@Jmeter:~/jmeter$ BASE_URL=http://<app-host> ./run-step-load.sh faas
[init] Removing 5 leftover file(s) from /home/<ssh-user>/jmeter/scripts/results
======================================
 PDMS Step-Load Test
 ...
[08:42:02]  All 4 steps complete.
```

This file is what `compare_reps.py` anchors the Prometheus window on
(`anchored on General/Started at …`), so a run without it degrades to the warmup anchor.

---

## Validation

Both the staging folder and the final destination must pass all of these, derived from the
`monolith` baseline:

| Check | Requirement |
|---|---|
| File count | ≥ 177 (observed: **177** for `monolith`/`faas`/`microservices`, **180** for `microservices-async` (+3 kafka message dumps), **205** for `decentralized` (+20 `besu/`, +8 besu host bundle)) |
| `run.log` | last line contains `Completed successfully` |
| `General` | non-empty and contains `All 4 steps complete` |
| `jmeter.zip` | passes `unzip -t`, holds exactly 5 `.jtl`, one of them `WARMUP` |
| `app/` | an `app_logs_*` file exists and is non-empty |
| `dbAndKafka/` | `db_dump.dump` is identified by `file` as a PostgreSQL dump |
| `dbAndKafka/` | `topic_inventory_cloud.csv` lists ≥ 4 `pdms.*` topics |
| `hostMetrics/` | exactly **4** `host_metrics_*` folders — **5 for `decentralized`** (app, db, jmeter, prom, besu) |
| `besu/` | *(decentralized only)* a `besu_data_*` folder exists, and a `host_metrics_besu_*` bundle is present |
| `prometheus/` | ≥ 128 `.json` files found **recursively** under `prometheus/`, plus `queries_manifest.json`. Observed exactly 128 in all 74 collected reps: 126 metric JSONs + `queries_manifest.json` + 1 `tsdb_snapshot/*/meta.json`. Counting only the top-level metrics folder gives 127 and will look like a false failure. |

The host-folder count is **architecture-aware**: hardcoding 4 once caused a perfectly good
`decentralized` run — which correctly had 5 — to be rejected. The `besu/` checks are positive
assertions, so a silently missing Besu bundle cannot pass by coincidence of file count.

**Deliberate warning, not a failure:** the three `kafka_pdms_*_messages.jsonl` files currently
contain `kafka-console-consumer.sh: executable file not found` instead of messages, because
the Kafka CLI is missing on the broker container. Every run logs a loud `WARN` for this.
Making it fatal would halt the campaign on the very first run. Fix the broker, and the warning
disappears on its own.

The dump check exists because it has caught a real failure before: 14 of 15 runs in an earlier
campaign had a 132-byte `pg_dump: error: … FATAL: sorry, too many clients already` in place of
the database dump, and nothing noticed until much later.

---

## Behaviour worth knowing

**Resumable.** Gaps are recomputed on every start, so an interrupted campaign continues where
it stopped. Re-running after success prints `Nothing to do`.

**Reuses a staging folder if one already exists.** If `scripts/data_collector/<arch><rep><scenario>/`
is present and non-empty, the script skips the restart and the JMeter run and goes straight to
validation and copying. This is what makes an already-collected-but-not-placed run cost seconds
instead of 35 minutes — but it also means a *half-finished* staging folder from a crashed
attempt will be validated and rejected rather than re-run. The script says so explicitly and
prints the `rm -rf` command needed to force a fresh run.

**Stops at the first failure and deletes nothing.** Both the staging folder and any partial
destination are left exactly as they are. Failures are reported per check, so you see all the
reasons at once rather than one at a time.

**Nothing is placed unless validation passes.** The copy into `data/` happens only after the
staging folder is complete, so a failed run never leaves a half-populated `data/` folder.

**It inherits whatever is deployed.** `restart_all_services.sh` rebuilds the images from
`~/PDMS` on the app host, so each run uses whatever commit is checked out there at that moment.
The script does not pin, check out, or verify a version — manage that yourself before starting
a campaign, and keep it fixed for the duration if the runs are meant to be comparable.

---

## Failure modes fixed during the 2026-08 campaign

Each of these caused a real lost or corrupted run before it was fixed.

| Symptom | Cause | Fix |
|---|---|---|
| Collection hung 36 min, no output | Half-open TCP on the host-metrics `ssh`; the remote side had finished and the box was idle | `ConnectTimeout=20`, `ServerAliveInterval=15`, `ServerAliveCountMax=8` on both transfer functions in `collect_all_remote_data.sh` |
| Retry failed validation with 7 hostMetrics folders | The retry reused the partial staging folder from the failed attempt | The retry now `rm -rf`s the staging folder first |
| Good `decentralized` run rejected | Validator hardcoded "exactly 4 host folders"; decentralized correctly has 5 | Count is architecture-aware, plus positive `besu/` assertions |
| Besu artifacts silently absent | `collect_all_remote_data.sh` used `<ssh-user>@` for the Besu VM; that account has no `docker.sock` access there | Both scripts use `<ssh-user>@` |
| JMeter exited 127 on every step | `~/.bashrc` adds `/opt/jmeter/bin` but returns early for non-interactive shells | `PATH` exported inside the remote command |
| Archive held 182 `.jtl` from several days | The load VM's results dir accumulates; collection silently fell back to it | Fresh-`.jtl` count guard; history moved to `~/jmeter/results_legacy_*` |
| Run contaminated by a previous campaign | Orphaned runner scripts survive a killed local campaign | `kill_stale_jmeter()` kills runner scripts **first**, then JVMs, and aborts if any survive |
| `pg_dump` produced a 132-byte error file | `FATAL: sorry, too many clients already` | Connections drained before collection; `max_connections=250` |
| Script died with `exit 2` mid-run | The running script was edited in place — bash reads by byte offset | Never edit a running script; write atomically (`os.replace`) |

---

## Running a long campaign

One rep takes roughly **27–28 minutes** measured across the final campaign (≈2 min warm-up +
4 × 5 min steps + inter-step waits + ≈2 min collection), so a full 15-rep architecture is about
**7 hours**. Run it detached:

```bash
cd PDMS/scripts/data_collector
nohup ./auto_collect.sh microservices-async > campaign.out 2>&1 &
tail -f campaign.out
```

The script also writes its own `campaign_<arch>_<timestamp>.log` in the same directory,
containing the step-by-step trace plus the full stdout of the restart and collect scripts.
The JMeter output itself lives in each run's `General` file.

---

## Test coverage

Validated in a sandbox with `restart_all_services.sh`, `collect_all_remote_data.sh` and `ssh`
replaced by stubs, so no VM was touched:

| # | Scenario | Result |
|---|---|---|
| 1 | Bad scenario / bad rep / reversed range / unknown arch / no args | rejected with a clear message, exit 2 |
| 2 | Happy path, single rep | 179 files placed, correct layout, `General` header correct, staging kept |
| 3 | `restart_all_services.sh` fails | stops at step 1, nothing placed |
| 4 | ssh/JMeter connection fails | stops at step 3, partial output kept for inspection |
| 5 | JMeter runs but never prints `All 4 steps complete` | stops at step 3, nothing placed |
| 6 | Collector fails | stops at step 4, nothing placed |
| 7 | Leftover incomplete staging folder | rejected, remedy printed, nothing placed |
| 8 | Recovery after deleting that folder | full cycle runs, run placed |
| 9 | Already-populated rep | skipped, no infrastructure touched |
| 10 | Ordering + stop-at-first-failure across scenarios | mix → read → writte, halts on first bad rep |
| 11 | Full sweep to completion | all 15 cells filled |
| 12 | Idempotent re-run | `Nothing to do` |
| 13 | Runner mapping | mix→step-load, read→read-heavy, writte→write-heavy |

The validator was also run against real collected data — `monolith/mix/1`,
`monolith/writte/5`, `microservices/read/1`, `faas/writte/3`, `microservices-async/read/1` and
the staged `microservices-async2read` all pass; an empty folder fails with all 10 reasons
listed.

### A bug this testing caught

The first version checked the archive with `unzip -l … | grep -q 'WARMUP'`. Because `grep -q`
exits at the first match, it SIGPIPEs `unzip`, and under `set -o pipefail` that non-zero status
propagated — so **valid** archives were reported as missing their warmup phase. Small zips
happened to pass, large ones failed, which is exactly the kind of intermittent behaviour that
is painful to diagnose at 3 a.m. mid-campaign. The listing is now captured once into a variable
and matched with `case`.
