#!/bin/bash
# Post-Test Data Collection Script
# Automatically collects JMeter results, database dumps, logs, and metrics after each test run
# Usage: ./collect_test_data.sh microservices-async mixed 1745668800 1745671200
# Arguments: VARIANT SCENARIO START_TS END_TS

set -e  # Exit on error

VARIANT=${1:-monolith}
SCENARIO=${2:-mixed}
START_TS=${3:?Error: START_TS (Unix timestamp) required as 3rd argument}
END_TS=${4:?Error: END_TS (Unix timestamp) required as 4th argument}
OUTDIR="test_artifacts/${VARIANT}_${SCENARIO}_$(date +%Y%m%d_%H%M%S)"

mkdir -p "$OUTDIR"
echo "🔄 Collecting test artifacts to: $OUTDIR"
echo ""

# ============================================================================
# 1. JMeter Results
# ============================================================================
echo "📊 Step 1: JMeter Results"
if cp scripts/results/*.jtl "$OUTDIR/" 2>/dev/null; then
  FILE_COUNT=$(ls "$OUTDIR"/*.jtl 2>/dev/null | wc -l)
  echo "✓ JMeter results ($FILE_COUNT .jtl files)"
else
  echo "⚠ No JMeter .jtl files found in scripts/results/"
fi
echo ""

# ============================================================================
# 2. Database Dump
# ============================================================================
echo "💾 Step 2: Database Dump (compressed)"
if docker exec -t pdms-postgres pg_dump -U pdms_user -Fc pdms_db > "$OUTDIR/db_dump.dump" 2>/dev/null; then
  SIZE=$(du -h "$OUTDIR/db_dump.dump" | cut -f1)
  echo "✓ Database dump ($SIZE)"
else
  echo "⚠ Could not export database (container pdms-postgres not running?)"
fi
echo ""

# ============================================================================
# 3. Application Logs
# ============================================================================
echo "📝 Step 3: Application Logs"
if docker compose -f docker-compose.${VARIANT}.yml logs --timestamps > "$OUTDIR/app_logs.txt" 2>/dev/null; then
  WARNS=$(grep -c "WARN" "$OUTDIR/app_logs.txt" || echo 0)
  ERRORS=$(grep -c "ERROR" "$OUTDIR/app_logs.txt" || echo 0)
  GCS=$(grep -c -i "gc\|full gc" "$OUTDIR/app_logs.txt" || echo 0)
  TIMEOUTS=$(grep -c -i "timeout" "$OUTDIR/app_logs.txt" || echo 0)

  echo "✓ Application logs"
  echo "  - WARNINGs: $WARNS"
  echo "  - ERRORs: $ERRORS"
  echo "  - GC events: $GCS"
  echo "  - Timeouts: $TIMEOUTS"
else
  echo "⚠ Could not export logs (variant docker-compose.${VARIANT}.yml not found?)"
fi
echo ""

# ============================================================================
# 4. Kafka Messages (Async Variants Only)
# ============================================================================
if [[ "$VARIANT" == *"async"* ]]; then
  echo "📡 Step 4: Kafka Messages"
  for topic in pdms.persons pdms.consents pdms.snapshots; do
    if docker exec pdms-kafka kafka-console-consumer.sh \
      --bootstrap-server localhost:9092 \
      --topic "$topic" \
      --from-beginning \
      --timeout-ms 5000 \
      > "$OUTDIR/kafka_${topic//./_}_messages.jsonl" 2>/dev/null; then
      MSGCOUNT=$(wc -l < "$OUTDIR/kafka_${topic//./_}_messages.jsonl")
      echo "✓ Kafka topic $topic ($MSGCOUNT messages)"
    else
      echo "⚠ Could not export Kafka topic $topic (broker not running?)"
    fi
  done
  echo ""
fi

# ============================================================================
# 5. Prometheus Metrics
# ============================================================================
echo "📈 Step 5: Prometheus Metrics"
PROMETHEUS="http://<observability-host>:9090"
mkdir -p "$OUTDIR/prometheus"

# Define queries: name:query pairs
declare -A QUERIES=(
  ["p99_latency"]="histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[1m]))"
  ["rps"]="rate(http_server_requests_seconds_count[1m])"
  ["heap"]="jvm_memory_used_bytes{area='heap'}"
  ["cpu"]="rate(container_cpu_usage_seconds_total[1m])"
  ["db_connections"]="hikaricp_connections_active"
)

if [[ "$VARIANT" == *"async"* ]]; then
  QUERIES["kafka_lag"]="kafka_consumer_records_lag_sum"
fi

for name in "${!QUERIES[@]}"; do
  query="${QUERIES[$name]}"
  if curl -s "$PROMETHEUS/api/v1/query_range" \
    --data-urlencode "query=$query" \
    --data-urlencode "start=$START_TS" \
    --data-urlencode "end=$END_TS" \
    --data-urlencode "step=15s" \
    > "$OUTDIR/prometheus/metrics_${name}.json" 2>/dev/null; then
    echo "✓ Prometheus: $name"
  else
    echo "⚠ Could not query Prometheus: $name (server may be unavailable)"
  fi
done
echo ""

# ============================================================================
# 6. Metadata
# ============================================================================
echo "📋 Step 6: Metadata"
cat > "$OUTDIR/metadata.txt" <<EOF
=== Test Run Metadata ===

Variant:           $VARIANT
Scenario:          $SCENARIO
Date:              $(date)
Git Commit:        $(git rev-parse HEAD 2>/dev/null || echo "N/A")
Git Branch:        $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "N/A")

Start Timestamp:   $START_TS (Unix seconds)
End Timestamp:     $END_TS (Unix seconds)
Test Duration:     $(( $END_TS - $START_TS )) seconds

Thread Steps:      10 25 50 75 100
Step Duration:     5 minutes each
Total Test Time:   ~35 minutes (including warm-up/cool-down)

Repetition:        1 of 5
Notes:             [Add any observations: errors, anomalies, system behavior]

=== Collection Summary ===

Results Directory: $OUTDIR
JMeter Files:      $(ls "$OUTDIR"/*.jtl 2>/dev/null | wc -l) files
Database Dump:     $([ -f "$OUTDIR/db_dump.dump" ] && echo "yes" || echo "no")
Application Logs:  $([ -f "$OUTDIR/app_logs.txt" ] && echo "yes" || echo "no")
Kafka Messages:    $([ -d "$OUTDIR" ] && ls "$OUTDIR"/kafka_*.jsonl 2>/dev/null | wc -l || echo "0") files
Prometheus Metrics: $([ -d "$OUTDIR/prometheus" ] && ls "$OUTDIR"/prometheus/*.json 2>/dev/null | wc -l || echo "0") files

Collection Time:   $(date)
EOF

echo "✓ Metadata recorded"
echo ""

# ============================================================================
# Summary
# ============================================================================
echo "✅ DATA COLLECTION COMPLETE"
echo ""
echo "📦 Artifacts stored in: $OUTDIR"
echo ""
echo "📦 Next steps:"
echo "   1. Review metadata and any observed anomalies"
echo "   2. Archive artifacts for storage:"
echo "      tar -czf test_artifacts_${VARIANT}_${SCENARIO}_$(date +%Y%m%d_%H%M%S).tar.gz $OUTDIR"
echo "   3. Upload to version control or archive storage"
echo ""
echo "📊 Analysis:"
echo "   - JMeter results: .jtl files import into JMeter GUI or parse with custom scripts"
echo "   - Prometheus metrics: JSON files can be analyzed with Python/R for charts"
echo "   - Database dump: restore with: pg_restore -d pdms_db < db_dump.dump"
echo "   - Application logs: grep for patterns (WARN, ERROR, GC, timeout)"
echo ""
