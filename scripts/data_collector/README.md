# Data Collection Scripts

Collection scripts for post-test artifact gathering — one script per VM.

> **These per-VM manual steps are no longer how campaign data is collected.** Use
> `auto_collect.sh` (see `AUTO_COLLECT.md`), which drives `restart_all_services.sh`,
> the JMeter runner, `collect_all_remote_data.sh`, and validation in one unattended cycle.
> The commands below remain useful for ad-hoc collection from a single VM.
>
> **Infrastructure (updated 2026-08-14):** 5 VMs, all `Standard_D2s_v6`, Sweden Central, UTC —
> JMeter `<jmeter-host>`, DB+Kafka `<db-host>`, Observability `<observability-host>`,
> APP `<app-host>`, and **Besu `<besu-host>` (user `<ssh-user>`, `decentralized` only)**.
> The IPs previously listed in this file were from an earlier deployment.

## Quick Start

After your test finishes, run these scripts **in parallel** on each VM:

### VM 1: JMeter Machine (Local)
```bash
cd jmeter/data_collector/
chmod +x collect_jmeter_results.sh
./collect_jmeter_results.sh monolith mixed
```
Output: `test_artifacts/monolith_mixed_TIMESTAMP/`

### VM 2: APP Server (<app-host>)
```bash
# Copy script there first
scp collect_app_logs.sh user@<app-host>:~/

# SSH and run
ssh user@<app-host>
chmod +x collect_app_logs.sh
./collect_app_logs.sh monolith
```
Output: `/tmp/app_logs_monolith_TIMESTAMP/`

### VM 3: DB + Kafka (<db-host>)
```bash
# Copy script there first
scp collect_db_kafka.sh user@<db-host>:~/

# SSH and run
ssh user@<db-host>
chmod +x collect_db_kafka.sh
./collect_db_kafka.sh monolith mixed 1745668800 1745671200
```
Output: `/tmp/db_kafka_monolith_mixed_TIMESTAMP/`

### VM 4: Observability (<observability-host>)
```bash
# Copy script there first
scp collect_prometheus_metrics.sh user@<observability-host>:~/

# SSH and run
ssh user@<observability-host>
chmod +x collect_prometheus_metrics.sh
./collect_prometheus_metrics.sh 1745668800 1745671200
```
Output: `/tmp/prometheus_metrics_TIMESTAMP/`


### VM 5: Besu (<besu-host>) — `decentralized` only
```bash
# Besu has its own VM since 2026-08 and runs as <ssh-user>
# (<ssh-user> has no docker.sock permission there)
scp -i ~/Downloads/besu.pem collect_besu_data.sh <ssh-user>@<besu-host>:~/
ssh -i ~/Downloads/besu.pem <ssh-user>@<besu-host>
chmod +x collect_besu_data.sh
./collect_besu_data.sh decentralized
```
Output: `~/besu_data_decentralized_TIMESTAMP/` — chain size, RPC snapshots,
`docker_stats_series.csv`, and `besu_prometheus_metrics.txt` (needs `--metrics-enabled`
and port 9545 published).

---

## Consolidate All Artifacts

After all 4 VMs finish, pull everything to JMeter VM:

```bash
OUTDIR="test_artifacts/monolith_mixed_20260423_104530"
mkdir -p "$OUTDIR/prometheus"

# Fetch from each VM
scp user@<app-host>:/tmp/app_logs_*/* "$OUTDIR/" 2>/dev/null
scp user@<db-host>:/tmp/db_kafka_*/* "$OUTDIR/" 2>/dev/null
scp user@<observability-host>:/tmp/prometheus_metrics_*/* "$OUTDIR/prometheus/" 2>/dev/null

# Verify
ls -lh "$OUTDIR"
```

---

## Script Details

| Script | VM | Input | Output |
|--------|-----|-------|--------|
| `collect_jmeter_results.sh` | JMeter | variant, scenario | `.jtl` files |
| `collect_app_logs.sh` | APP | variant | `app_logs.txt` |
| `collect_db_kafka.sh` | DB+Kafka | variant, scenario, start_ts, end_ts | `db_dump.dump`, Kafka messages |
| `collect_prometheus_metrics.sh` | Observability | start_ts, end_ts | 5 JSON metric files |

---

## Getting Timestamps

When test starts/ends, record Unix timestamp:
```bash
TEST_START=$(date +%s)
# ... run your test ...
TEST_END=$(date +%s)
echo "Test ran from $TEST_START to $TEST_END"
```

Or calculate from date:
```bash
date -d "2026-04-23 10:45:00" +%s
# Output: 1745668800
```

---

## Archive for Storage

```bash
tar -czf test_artifacts_monolith_mixed_20260423.tar.gz test_artifacts/monolith_mixed_20260423_104530/
```

Size: ~500MB - 2GB depending on test duration.
