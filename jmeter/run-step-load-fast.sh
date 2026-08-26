#!/usr/bin/env bash
# Step-load test runner for PDMS benchmarking — FAST variant.
# Same as run-step-load.sh but ~35s per step. Total runtime ≈ 5 minutes.
# Use this to quickly verify all steps work before committing to a full run.
#
# Usage:
#   ./run-step-load-fast.sh [variant]
#
# Examples:
#   ./run-step-load-fast.sh monolith
#   ./run-step-load-fast.sh microservices-async
#
# Override any parameter via env:
#   STEPS="10 25 50" STEP_DURATION=35 BASE_URL=http://my-cloud-vm:8080 ./run-step-load-fast.sh monolith
#
# Pass a fresh JWT token when the default expires:
#   JWT_TOKEN="<jwt-token>" ./run-step-load-fast.sh monolith

VARIANT="${1:-monolith}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JMX="$SCRIPT_DIR/scripts/PDMS-Combined-Benchmark.jmx"
RESULTS_DIR="$SCRIPT_DIR/scripts/results"

BASE_URL="${BASE_URL:-http://localhost}"
JWT_TOKEN="${JWT_TOKEN:-}"
# Campaign ladder. Changed 2026-08-13 from 10 20 40 60: that ladder began at or
# above saturation on this hardware, so every step measured a throughput ceiling
# and none measured per-request cost. Override with the env var to reproduce the
# superseded campaign.
STEPS="${STEPS:-2 5 15 50}"
STEP_DURATION="${STEP_DURATION:-35}"
RAMP="${RAMP:-5}"

# Microservices-async splits traffic across three ports; monolith uses 8080 for all
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

WORKLOAD="Person Create/Update/Get → Consent Create/List/Withdraw/Delete → Snapshot Get"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

step_stats() {
  local file="$1"
  python3 - "$file" <<'EOF'
import csv, sys
rows = [r for r in csv.DictReader(open(sys.argv[1])) if r.get('elapsed')]
if not rows:
    print("  No data in result file"); sys.exit(0)
elapsed = sorted(int(r['elapsed']) for r in rows)
errors  = sum(1 for r in rows if r['success'] == 'false')
total   = len(elapsed)
avg     = sum(elapsed) // total
p95     = elapsed[int(total * 0.95)]
p99     = elapsed[int(total * 0.99)]
print(f"  Requests: {total} | Errors: {errors} ({100*errors/total:.1f}%) | Avg: {avg}ms | p95: {p95}ms | p99: {p99}ms")
EOF
}

mkdir -p "$RESULTS_DIR"

echo "======================================"
echo " PDMS Step-Load Test (FAST)"
echo " Variant   : $VARIANT"
echo " Target URL: $BASE_URL (person:$PERSON_PORT consent:$CONSENT_PORT snapshot:$SNAPSHOT_PORT)"
echo " Steps     : $STEPS"
echo " Duration  : ${STEP_DURATION}s per step"
echo " Ramp      : ${RAMP}s"
echo " Est. total: ~5 min"
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
log "[WARM-UP] 5 threads / 15s — $WORKLOAD"
jmeter -n \
  -t "$JMX" \
  "${COMMON_PROPS[@]}" \
  -Jthreads=5 \
  -Jduration=15 \
  -l "$RESULTS_DIR/WARMUP_${VARIANT}_$(date +%Y%m%d_%H%M%S).jtl" \
  2>&1 | grep "^summary =" | tail -1
log "[WARM-UP] Done. Waiting 5s to stabilise..."
sleep 5

STEP_NUM=0
TOTAL_STEPS=$(echo "$STEPS" | wc -w | tr -d ' ')

for THREADS in $STEPS; do
  STEP_NUM=$((STEP_NUM + 1))
  TIMESTAMP=$(date +%Y%m%d_%H%M%S)
  LABEL="${VARIANT}_fast_t${THREADS}"
  RESULT_FILE="$RESULTS_DIR/${LABEL}_${TIMESTAMP}.jtl"

  echo ""
  log "[STEP $STEP_NUM/$TOTAL_STEPS] threads=$THREADS | ${STEP_DURATION}s | $WORKLOAD"

  jmeter -n \
    -t "$JMX" \
    "${COMMON_PROPS[@]}" \
    -Jthreads="$THREADS" \
    -Jduration="$STEP_DURATION" \
    -l "$RESULT_FILE" \
    2>&1 | grep "^summary =" | tail -1

  EXIT_CODE=${PIPESTATUS[0]}
  if [ $EXIT_CODE -ne 0 ]; then
    log "[STEP $STEP_NUM/$TOTAL_STEPS] ERROR: JMeter exited with code $EXIT_CODE"
  else
    step_stats "$RESULT_FILE"
  fi

  if [ "$STEP_NUM" -lt "$TOTAL_STEPS" ]; then
    sleep 5
  fi
done

echo ""
echo "======================================"
log " All $TOTAL_STEPS steps complete."
echo " Results : $RESULTS_DIR"
echo " Finished: $(date '+%Y-%m-%d %H:%M:%S')"
echo "======================================"
