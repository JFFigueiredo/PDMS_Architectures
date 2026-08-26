#!/usr/bin/env bash
# Step-load test runner for PDMS — Write-Heavy variant.
# Each iteration: 8 writes (Person Create/Update, Consent Create x3, Consent Withdraw x3)
#                + 2 reads  (Person Get, Consents List)  →  80% / 20% write/read ratio.
#
# Usage:
#   ./run-write-heavy.sh [variant]
#
# Examples:
#   ./run-write-heavy.sh monolith
#   ./run-write-heavy.sh microservices-async
#
# Override any parameter via env:
#   STEPS="10 25 50" STEP_DURATION=180 BASE_URL=http://my-cloud-vm:8080 ./run-write-heavy.sh monolith
#
# Pass a fresh JWT token when the default expires:
#   JWT_TOKEN="<jwt-token>" ./run-write-heavy.sh monolith

VARIANT="${1:-monolith}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JMX="$SCRIPT_DIR/scripts/PDMS-Write-Heavy-Benchmark.jmx"
RESULTS_DIR="$SCRIPT_DIR/scripts/results"

# --- per-run isolation -------------------------------------------------------
# Each capture must contain only this run's .jtl files. collect_jmeter_results.sh
# copies EVERY .jtl in this directory, so anything left behind is folded into the
# next repetition's archive and has to be untangled later by attribution.
# Leftovers are deleted here: COLLECT A RUN BEFORE STARTING THE NEXT ONE.
if [ -d "$RESULTS_DIR" ] && [ -n "$(ls -A "$RESULTS_DIR" 2>/dev/null)" ]; then
  echo "[init] Removing $(ls -1 "$RESULTS_DIR" | wc -l | tr -d ' ') leftover file(s) from $RESULTS_DIR"
  rm -rf "${RESULTS_DIR:?}"/*
fi
mkdir -p "$RESULTS_DIR"
# -----------------------------------------------------------------------------


BASE_URL="${BASE_URL:-http://localhost}"
JWT_TOKEN="${JWT_TOKEN:-}"
# Campaign ladder. Changed 2026-08-13 from 10 20 40 60: that ladder began at or
# above saturation on this hardware, so every step measured a throughput ceiling
# and none measured per-request cost. Override with the env var to reproduce the
# superseded campaign.
STEPS="${STEPS:-2 5 15 50}"
STEP_DURATION="${STEP_DURATION:-300}"
RAMP="${RAMP:-30}"

if [[ "$VARIANT" == *"microservices"* ]]; then
  PERSON_PORT="${PERSON_PORT:-8080}"
  CONSENT_PORT="${CONSENT_PORT:-8082}"
  SNAPSHOT_PORT="${SNAPSHOT_PORT:-8081}"
  HEALTH_PORT="${HEALTH_PORT:-8080}"
else
  PERSON_PORT="${PERSON_PORT:-8080}"
  CONSENT_PORT="${CONSENT_PORT:-8080}"
  SNAPSHOT_PORT="${SNAPSHOT_PORT:-8080}"
  HEALTH_PORT="${HEALTH_PORT:-8080}"
fi

export JVM_ARGS="${JVM_ARGS:--Xms512m -Xmx2g}"

WORKLOAD="Person Create/Update → Consent Create x3 → Consent Withdraw x3 → Person Get → Consents List"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

step_stats() {
  local file="$1" threads="$2"
  python3 - "$file" "$threads" <<'EOF'
import csv, sys
file, threads = sys.argv[1], sys.argv[2]
rows = [r for r in csv.DictReader(open(file)) if r.get('elapsed')]
if not rows:
    print("  No data in result file")
    sys.exit(0)
elapsed = sorted(int(r['elapsed']) for r in rows)
errors  = sum(1 for r in rows if r['success'] == 'false')
total   = len(elapsed)
avg     = sum(elapsed) // total
p95     = elapsed[int(total * 0.95)]
p99     = elapsed[int(total * 0.99)]
err_pct = 100 * errors / total
print(f"  Requests: {total} | Errors: {errors} ({err_pct:.1f}%) | Avg: {avg}ms | p95: {p95}ms | p99: {p99}ms")
EOF
}

mkdir -p "$RESULTS_DIR"

echo "======================================"
echo " PDMS Write-Heavy Step-Load Test"
echo " Variant   : $VARIANT"
echo " Workload  : 80% writes / 20% reads"
echo " Target URL: $BASE_URL (person:$PERSON_PORT consent:$CONSENT_PORT)"
echo " Steps     : $STEPS"
echo " Duration  : ${STEP_DURATION}s per step"
echo " Ramp      : ${RAMP}s"
echo " Started   : $(date '+%Y-%m-%d %H:%M:%S')"
echo "======================================"

check_health() {
  local port="$1" label="$2"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL:$port/actuator/health" 2>/dev/null)
  if [ "$status" = "200" ]; then
    log "Health OK — $label :$port"
  else
    log "ERROR: $label :$port returned HTTP $status — is the service running?"
    exit 1
  fi
}

if [[ "$VARIANT" == *"microservices"* ]]; then
  check_health "$PERSON_PORT"   "person-service"
  check_health "$CONSENT_PORT"  "consent-service"
  check_health "$SNAPSHOT_PORT" "snapshot-service"
else
  check_health "$HEALTH_PORT" "$VARIANT"
fi

COMMON_PROPS=(
  -Jbase_url="$BASE_URL"
  -Jperson_port="$PERSON_PORT"
  -Jconsent_port="$CONSENT_PORT"
  -Jsnapshot_port="$SNAPSHOT_PORT"
  -Jscenario="$VARIANT"
  -Jramp="$RAMP"
)
[ -n "$JWT_TOKEN" ] && COMMON_PROPS+=(-Jjwt_token="$JWT_TOKEN")

# Warm-up
echo ""
log "[WARM-UP] 5 threads / 120s — results discarded"
jmeter -n \
  -t "$JMX" \
  "${COMMON_PROPS[@]}" \
  -Jthreads=5 \
  -Jduration=120 \
  -l "$RESULTS_DIR/WARMUP_wh_${VARIANT}_$(date +%Y%m%d_%H%M%S).jtl" \
  2>&1 | grep --line-buffered "^summary ="
log "[WARM-UP] Done. Waiting 30s to stabilise..."
sleep 30

STEP_NUM=0
TOTAL_STEPS=$(echo "$STEPS" | wc -w | tr -d ' ')

for THREADS in $STEPS; do
  STEP_NUM=$((STEP_NUM + 1))
  TIMESTAMP=$(date +%Y%m%d_%H%M%S)
  LABEL="${VARIANT}_wh_t${THREADS}"
  RESULT_FILE="$RESULTS_DIR/${LABEL}_${TIMESTAMP}.jtl"

  echo ""
  log "[STEP $STEP_NUM/$TOTAL_STEPS] threads=$THREADS | duration=${STEP_DURATION}s | $WORKLOAD"

  jmeter -n \
    -t "$JMX" \
    "${COMMON_PROPS[@]}" \
    -Jthreads="$THREADS" \
    -Jduration="$STEP_DURATION" \
    -l "$RESULT_FILE" \
    2>&1 | grep --line-buffered "^summary ="

  EXIT_CODE=${PIPESTATUS[0]}
  if [ $EXIT_CODE -ne 0 ]; then
    log "[STEP $STEP_NUM/$TOTAL_STEPS] ERROR: JMeter exited with code $EXIT_CODE"
  else
    log "[STEP $STEP_NUM/$TOTAL_STEPS] Results:"
    step_stats "$RESULT_FILE" "$THREADS"
  fi

  if [ "$STEP_NUM" -lt "$TOTAL_STEPS" ]; then
    log "[STEP $STEP_NUM/$TOTAL_STEPS] Waiting 30s before next step..."
    sleep 30
  fi
done

echo ""
echo "======================================"
log " All $TOTAL_STEPS steps complete."
echo " Results : $RESULTS_DIR"
echo " Finished: $(date '+%Y-%m-%d %H:%M:%S')"
echo "======================================"
