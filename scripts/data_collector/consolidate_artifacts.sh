#!/bin/bash
# Consolidate artifacts from all 4 VMs into single directory
# Usage: ./consolidate_artifacts.sh monolith mixed <app-host> <db-host> <observability-host>

VARIANT="${1:-monolith}"
SCENARIO="${2:-mixed}"
APP_VM="${3:-<app-host>}"
DB_VM="${4:-<db-host>}"
OBS_VM="${5:-<observability-host>}"

OUTDIR="./${VARIANT}_${SCENARIO}_consolidated_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUTDIR"

echo "Consolidating artifacts into: $OUTDIR"
echo ""

# From APP VM
echo "[1/3] Fetching from APP VM ($APP_VM)..."
scp -r "user@${APP_VM}:./app_logs_*" "$OUTDIR/" 2>/dev/null
APP_COUNT=$(find "$OUTDIR/app_logs_"* -name "app_logs.txt" 2>/dev/null | wc -l)
echo "OK - App logs ($APP_COUNT files)"

# From DB+Kafka VM
echo "[2/3] Fetching from DB+Kafka VM ($DB_VM)..."
scp -r "user@${DB_VM}:./db_kafka_*" "$OUTDIR/" 2>/dev/null
DB_COUNT=$(find "$OUTDIR/db_kafka_"* -name "db_dump.dump" 2>/dev/null | wc -l)
KAFKA_COUNT=$(find "$OUTDIR/db_kafka_"* -name "kafka_*.jsonl" 2>/dev/null | wc -l)
echo "OK - Database dump ($DB_COUNT), Kafka messages ($KAFKA_COUNT files)"

# From Observability VM
echo "[3/3] Fetching from Observability VM ($OBS_VM)..."
scp -r "user@${OBS_VM}:./prometheus_metrics_*" "$OUTDIR/" 2>/dev/null
PROM_COUNT=$(find "$OUTDIR/prometheus_metrics_"* -name "*.json" 2>/dev/null | wc -l)
echo "OK - Prometheus metrics ($PROM_COUNT files)"

echo ""
echo "DONE - All artifacts consolidated in: $OUTDIR"
echo "Files:"
ls -lh "$OUTDIR" | tail -n +2
