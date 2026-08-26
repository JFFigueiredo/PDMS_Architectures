#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./run_historical_grafana.sh <PROM_METRICS_DIR>
#
# Example:
#   ./run_historical_grafana.sh "../RetriviedData/Error/prometheus_metrics_monolith_20260427_175120"

PROM_METRICS_DIR_INPUT="${1:-}"

if [[ -z "${PROM_METRICS_DIR_INPUT}" ]]; then
  echo "Usage: $0 <PROM_METRICS_DIR>"
  exit 1
fi

CALLER_DIR="$(pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

# Resolve dataset path from the caller's current directory so relative paths
# passed by the user work regardless of the script changing directories.
PROM_METRICS_DIR="$(python3 - <<'PY' "${CALLER_DIR}" "${SCRIPT_DIR}" "${PROM_METRICS_DIR_INPUT}"
import sys
from pathlib import Path
caller_base = Path(sys.argv[1]).resolve()
script_base = Path(sys.argv[2]).resolve()
raw = Path(sys.argv[3])
if raw.is_absolute():
    print(str(raw.resolve()))
else:
    caller_resolved = (caller_base / raw).resolve()
    script_resolved = (script_base / raw).resolve()
    if caller_resolved.exists():
        print(str(caller_resolved))
    else:
        print(str(script_resolved))
PY
)"

if [[ ! -d "${PROM_METRICS_DIR}" ]]; then
  echo "ERROR: Prometheus metrics folder not found: ${PROM_METRICS_DIR}"
  echo "Hint: pass a valid folder like ../RetriviedData/Error/prometheus_metrics_monolith_20260427_175120"
  echo "Available folders under ../RetriviedData/Error:"
  ls -1 "${SCRIPT_DIR}/../RetriviedData/Error" 2>/dev/null | rg '^prometheus_metrics_' || true
  exit 1
fi

# Required for "Response time total" panel (average, p95, p99).
REQUIRED_RESPONSE_FILES=(
  "http_mean_latency.json"
  "http_p95_latency.json"
  "http_p99_latency.json"
)

MISSING_REQUIRED=()
for f in "${REQUIRED_RESPONSE_FILES[@]}"; do
  if [[ ! -f "${PROM_METRICS_DIR}/${f}" ]]; then
    MISSING_REQUIRED+=("${f}")
  fi
done

if [[ ${#MISSING_REQUIRED[@]} -gt 0 ]]; then
  echo "ERROR: Missing required response-time files in export:"
  for f in "${MISSING_REQUIRED[@]}"; do
    echo "  - ${f}"
  done
  echo "Please re-run collect_prometheus_metrics.sh and ensure these files are exported."
  exit 1
fi

echo "==> Starting InfluxDB..."
docker compose -f docker-compose.historical.yml up -d

wait_for_influx() {
  local health_url="${INFLUX_URL:-http://127.0.0.1:8086}/health"
  local max_wait_seconds="${INFLUX_STARTUP_TIMEOUT_SECONDS:-60}"
  local waited=0

  echo "==> Waiting for InfluxDB readiness (${health_url})..."
  while (( waited < max_wait_seconds )); do
    if curl -fsS --max-time 2 "${health_url}" >/dev/null 2>&1; then
      echo "==> InfluxDB is ready."
      return 0
    fi
    sleep 1
    ((waited += 1))
  done

  echo "ERROR: InfluxDB did not become ready within ${max_wait_seconds}s."
  echo "Hint: check container logs with: docker logs pdms-historical-influxdb"
  return 1
}

wait_for_influx

echo "==> Importing historical data from: ${PROM_METRICS_DIR}"
echo "==> Required response-time files found: ${REQUIRED_RESPONSE_FILES[*]}"
PROM_METRICS_DIR="${PROM_METRICS_DIR}" python3 import_historical_to_influx.py

echo "==> Rebuilding dashboard for dataset..."
PROM_METRICS_DIR="${PROM_METRICS_DIR}" python3 build_influx_dashboard.py
if [[ "${TUNE_DASHBOARD:-1}" == "1" ]]; then
  echo "==> Applying dashboard tuning overrides (TUNE_DASHBOARD=1)..."
  PROM_METRICS_DIR="${PROM_METRICS_DIR}" python3 tune_influx_dashboard.py
else
  echo "==> Skipping tune_influx_dashboard.py (TUNE_DASHBOARD=0)."
fi

echo "==> Starting Grafana..."
docker compose -f docker-compose.grafana-only.yml up -d

echo ""
echo "Done."
echo "InfluxDB: http://localhost:8086"
echo "Grafana : http://localhost:3001 (admin/admin)"
