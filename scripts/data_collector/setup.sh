#!/bin/bash
# Setup: Copy collection scripts to each VM
# Usage: ./setup.sh user <app-host> <db-host> <observability-host>

USERNAME="${1:-user}"
APP_VM="${2:-<app-host>}"
DB_VM="${3:-<db-host>}"
OBS_VM="${4:-<observability-host>}"

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

echo "Setting up data collection scripts on all VMs"
echo "========================================"
echo ""

# Copy to APP VM
echo "[1/3] Copying to APP VM ($APP_VM)..."
scp "$SCRIPT_DIR/collect_app_logs.sh" "${USERNAME}@${APP_VM}:~/" 2>/dev/null
ssh "${USERNAME}@${APP_VM}" "chmod +x collect_app_logs.sh" 2>/dev/null
echo "OK - collect_app_logs.sh ready"

# Copy to DB VM
echo "[2/3] Copying to DB+Kafka VM ($DB_VM)..."
scp "$SCRIPT_DIR/collect_db_kafka.sh" "${USERNAME}@${DB_VM}:~/" 2>/dev/null
ssh "${USERNAME}@${DB_VM}" "chmod +x collect_db_kafka.sh" 2>/dev/null
echo "OK - collect_db_kafka.sh ready"

# Copy to Observability VM
echo "[3/3] Copying to Observability VM ($OBS_VM)..."
scp "$SCRIPT_DIR/collect_prometheus_metrics.sh" "${USERNAME}@${OBS_VM}:~/" 2>/dev/null
ssh "${USERNAME}@${OBS_VM}" "chmod +x collect_prometheus_metrics.sh" 2>/dev/null
echo "OK - collect_prometheus_metrics.sh ready"

echo ""
echo "DONE - All scripts deployed"
echo ""
echo "Next steps:"
echo "1. Run your test: ./run-step-load.sh monolith"
echo "2. After test, run on each VM:"
echo "   - APP VM: ./collect_app_logs.sh monolith"
echo "   - DB+Kafka VM: ./collect_db_kafka.sh monolith mixed START_TS END_TS"
echo "   - Observability VM: ./collect_prometheus_metrics.sh START_TS END_TS"
echo "3. Consolidate: ./consolidate_artifacts.sh monolith mixed $APP_VM $DB_VM $OBS_VM"
