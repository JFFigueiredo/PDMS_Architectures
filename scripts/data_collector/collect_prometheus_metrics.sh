#!/usr/bin/env bash
set -euo pipefail
# Collects ALL Prometheus metrics: dashboard queries + full hardcoded set.
# Default time range: previous full hour.
#
# Usage:
#   ./collect_prometheus_metrics.sh [variant] [START_TS] [END_TS]
#
# Examples:
#   ./collect_prometheus_metrics.sh monolith
#   ./collect_prometheus_metrics.sh monolith 1745760000 1745763600

VARIANT="${1:-monolith}"

# Last rolling hour by default (now-3600s .. now)
_NOW=$(date +%s)
END_TS="${3:-$_NOW}"
START_TS="${2:-$((END_TS - 3600))}"
RANGE_SECONDS="$((END_TS - START_TS))"

STEP="15s"
PROMETHEUS="${PROMETHEUS:-http://localhost:9090}"
DASHBOARD_JSON="${DASHBOARD_JSON:-/home/<ssh-user>/grafana/dashboards/pdms-dashboard.json}"
OUTDIR="./prometheus_metrics_${VARIANT}_$(date +%Y%m%d_%H%M%S)"

mkdir -p "$OUTDIR"

ts_to_iso() { date -u -r "$1" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d "@$1" '+%Y-%m-%dT%H:%M:%SZ'; }

echo "============================================="
echo " Prometheus Metrics Collector"
echo " Variant : $VARIANT"
echo " From    : $(ts_to_iso "$START_TS")"
echo " To      : $(ts_to_iso "$END_TS")"
echo " Output  : $OUTDIR"
echo "============================================="

fetch() {
  local name="$1" query="$2"
  curl -sG "$PROMETHEUS/api/v1/query_range" \
    --data-urlencode "query=$query" \
    --data-urlencode "start=$START_TS" \
    --data-urlencode "end=$END_TS" \
    --data-urlencode "step=$STEP" \
    > "$OUTDIR/${name}.json"
  local status series
  status=$(python3 -c "import json,sys; d=json.load(open('$OUTDIR/${name}.json')); print(d.get('status','?'))" 2>/dev/null || echo "?")
  series=$(python3 -c "import json,sys; d=json.load(open('$OUTDIR/${name}.json')); print(len(d.get('data',{}).get('result',[])))" 2>/dev/null || echo "?")
  printf "%-8s %-50s %s\n" "$status" "$name" "${series} series"
}

# ── 1. DASHBOARD QUERIES (dynamic) ───────────────────────────────────────────
echo ""
echo "[1/7] Dashboard queries..."
if command -v jq &>/dev/null && [ -f "$DASHBOARD_JSON" ]; then
  jq -r '.. | .expr? // empty' "$DASHBOARD_JSON" 2>/dev/null \
    | grep -v '^$' | awk '!seen[$0]++' \
    > "$OUTDIR/dashboard_queries.txt"
  i=0
  while IFS= read -r expr; do
    [ -z "$expr" ] && continue
    i=$((i + 1))
    local file; file=$(printf '%s/dashboard_q_%03d.json' "$OUTDIR" "$i")
    curl -sG "$PROMETHEUS/api/v1/query_range" \
      --data-urlencode "query=$expr" \
      --data-urlencode "start=$START_TS" \
      --data-urlencode "end=$END_TS" \
      --data-urlencode "step=$STEP" \
      > "$file"
    local status series
    status=$(python3 -c "import json; d=json.load(open('$file')); print(d.get('status','?'))" 2>/dev/null || echo "?")
    series=$(python3 -c "import json; d=json.load(open('$file')); print(len(d.get('data',{}).get('result',[])))" 2>/dev/null || echo "?")
    printf "%-8s %-50s %s\n" "$status" "dashboard_q_$(printf '%03d' "$i")" "${series} series"
  done < "$OUTDIR/dashboard_queries.txt"
  echo "    → $i dashboard queries saved"
else
  echo "    SKIP — jq not found or dashboard not at $DASHBOARD_JSON"
fi

# ── 2. HTTP METRICS ───────────────────────────────────────────────────────────
echo ""
echo "[2/7] HTTP metrics..."
fetch "http_p50_latency"          "histogram_quantile(0.50, rate(http_server_requests_seconds_bucket[1m]))"
fetch "http_p75_latency"          "histogram_quantile(0.75, rate(http_server_requests_seconds_bucket[1m]))"
fetch "http_p95_latency"          "histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[1m]))"
fetch "http_p99_latency"          "histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[1m]))"
fetch "http_p999_latency"         "histogram_quantile(0.999, rate(http_server_requests_seconds_bucket[1m]))"
fetch "http_p50_by_endpoint"      "histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{uri!~'/actuator.*'}[1m])) by (le, method, uri))"
fetch "http_p95_by_endpoint"      "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri!~'/actuator.*'}[1m])) by (le, method, uri))"
fetch "http_p99_by_endpoint"      "histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{uri!~'/actuator.*'}[1m])) by (le, method, uri))"
fetch "http_mean_latency"         "rate(http_server_requests_seconds_sum{uri!~'/actuator.*'}[1m]) / rate(http_server_requests_seconds_count{uri!~'/actuator.*'}[1m])"
fetch "http_max_latency"          "max(http_server_requests_seconds_max{uri!~'/actuator.*'}) by (method, uri)"
fetch "http_rps"                  "rate(http_server_requests_seconds_count[1m])"
fetch "http_rps_no_actuator"      "sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*'}[1m]))"
fetch "http_rps_by_endpoint"      "sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*'}[1m])) by (method, uri)"
fetch "http_rps_by_method"        "sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*'}[1m])) by (method)"
fetch "http_rps_by_status"        "sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*'}[1m])) by (status)"
fetch "http_active_requests"      "http_server_requests_active_seconds_count"
fetch "http_error_rate_5xx"       "rate(http_server_requests_seconds_count{status=~'5..'}[1m])"
fetch "http_error_rate_4xx"       "rate(http_server_requests_seconds_count{status=~'4..'}[1m])"
fetch "http_5xx_by_endpoint"      "sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*',status=~'5..'}[1m])) by (uri, status)"
fetch "http_4xx_by_endpoint"      "sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*',status=~'4..'}[1m])) by (uri, status)"
fetch "http_5xx_percentage"       "(sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*',status=~'5..'}[1m])) / sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*'}[1m]))) * 100"
fetch "http_4xx_percentage"       "(sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*',status=~'4..'}[1m])) / sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*'}[1m]))) * 100"
fetch "http_success_percentage"   "(sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*',status=~'2..'}[1m])) / sum(rate(http_server_requests_seconds_count{uri!~'/actuator.*'}[1m]))) * 100"
fetch "http_success_rate"         "rate(http_server_requests_seconds_count{status='200'}[1m])"
fetch "http_total_requests"       "sum(http_server_requests_seconds_count{uri!~'/actuator.*'})"
fetch "http_total_requests_range" "sum(increase(http_server_requests_seconds_count{uri!~'/actuator.*'}[${RANGE_SECONDS}s]))"
fetch "http_request_sizes"        "sum(rate(http_server_request_bytes_sum[1m])) by (method)"
fetch "http_response_sizes"       "sum(rate(http_server_response_bytes_sum[1m])) by (method)"

# ── 3. JVM METRICS ────────────────────────────────────────────────────────────
echo ""
echo "[3/7] JVM metrics..."
fetch "jvm_heap_used"             "jvm_memory_used_bytes{area='heap'}"
fetch "jvm_heap_max"              "jvm_memory_max_bytes{area='heap'}"
fetch "jvm_heap_committed"        "jvm_memory_committed_bytes{area='heap'}"
fetch "jvm_heap_utilization_pct"  "(jvm_memory_used_bytes{area='heap'} / jvm_memory_max_bytes{area='heap'}) * 100"
fetch "jvm_nonheap_used"          "jvm_memory_used_bytes{area='nonheap'}"
fetch "jvm_nonheap_max"           "jvm_memory_max_bytes{area='nonheap'}"
fetch "jvm_memory_pools_all"      "jvm_memory_used_bytes"
fetch "jvm_eden_used"             "jvm_memory_used_bytes{id='G1 Eden Space'}"
fetch "jvm_old_gen_used"          "jvm_memory_used_bytes{id='G1 Old Gen'}"
fetch "jvm_survivor_used"         "jvm_memory_used_bytes{id=~'G1.*Survivor.*'}"
fetch "jvm_threads_live"          "jvm_threads_live_threads"
fetch "jvm_threads_daemon"        "jvm_threads_daemon_threads"
fetch "jvm_threads_peak"          "jvm_threads_peak_threads"
fetch "jvm_threads_states"        "jvm_threads_states_threads"
fetch "jvm_gc_pause_time"         "rate(jvm_gc_pause_seconds_sum[1m])"
fetch "jvm_gc_pause_count"        "rate(jvm_gc_pause_seconds_count[1m])"
fetch "jvm_gc_pause_mean"         "rate(jvm_gc_pause_seconds_sum[1m]) / rate(jvm_gc_pause_seconds_count[1m])"
fetch "jvm_gc_pause_p99"          "histogram_quantile(0.99, rate(jvm_gc_pause_seconds_bucket[1m]))"
fetch "jvm_gc_live_data_size"     "jvm_gc_live_data_size_bytes"
fetch "jvm_gc_max_data_size"      "rate(jvm_gc_max_data_size_bytes[1m])"
fetch "jvm_gc_memory_promoted"    "rate(jvm_gc_memory_promoted_bytes_total[1m])"
fetch "jvm_gc_memory_allocated"   "rate(jvm_gc_memory_allocated_bytes_total[1m])"
fetch "jvm_classes_loaded"        "jvm_classes_loaded_classes"
fetch "jvm_classes_unloaded"      "rate(jvm_classes_unloaded_classes_total[1m])"
fetch "jvm_compilation_time"      "rate(jvm_compilation_time_ms_total[1m])"
fetch "jvm_cpu_usage"             "process_cpu_usage"
fetch "system_cpu_usage"          "system_cpu_usage * 100"
fetch "system_cpu_count"          "system_cpu_count"
fetch "process_resident_memory"   "process_resident_memory_bytes"
fetch "process_virtual_memory"    "process_virtual_memory_bytes"
fetch "process_cpu_seconds"       "process_cpu_seconds_total"
fetch "process_uptime"            "process_uptime_seconds"
fetch "process_files_open"        "process_files_open_files"
fetch "process_files_max"         "process_files_max_files"

# ── 4. DATABASE METRICS ───────────────────────────────────────────────────────
echo ""
echo "[4/7] Database metrics..."
fetch "db_connections_active"       "hikaricp_connections_active"
fetch "db_connections_idle"         "hikaricp_connections_idle"
fetch "db_connections_pending"      "hikaricp_connections_pending"
fetch "db_connections_max"          "hikaricp_connections_max"
fetch "db_connections_min"          "hikaricp_connections_min"
fetch "db_connections_total"        "hikaricp_connections_active + hikaricp_connections_idle"
fetch "db_connections_timeout"      "hikaricp_connections_timeout_total"
fetch "db_connection_creation_mean" "hikaricp_connections_creation_seconds_sum / hikaricp_connections_creation_seconds_count"
fetch "db_connection_acquire_mean"  "rate(hikaricp_connections_acquire_seconds_sum[1m]) / rate(hikaricp_connections_acquire_seconds_count[1m])"
fetch "db_connection_acquire_count" "hikaricp_connections_acquire_seconds_count"
fetch "db_connection_usage_mean"    "rate(hikaricp_connections_usage_seconds_sum[1m]) / rate(hikaricp_connections_usage_seconds_count[1m])"
fetch "db_connection_usage_p95"     "histogram_quantile(0.95, rate(hikaricp_connections_usage_seconds_bucket[1m]))"
fetch "db_query_time_p95"           "histogram_quantile(0.95, rate(spring_data_repository_call_duration_seconds_bucket[1m]))"
fetch "db_slow_queries"             "rate(spring_data_repository_call_duration_seconds_sum{le='1.0'}[1m])"
fetch "db_transaction_rollbacks"    "rate(spring_data_transaction_rollback_total[1m])"

# ── 5. KAFKA METRICS ──────────────────────────────────────────────────────────
echo ""
echo "[5/7] Kafka metrics..."
fetch "kafka_producer_sent"            "rate(spring_kafka_producer_record_send_total[1m])"
fetch "kafka_producer_sent_total"      "spring_kafka_producer_record_send_total"
fetch "kafka_producer_failures"        "rate(spring_kafka_producer_record_send_failed_total[1m])"
fetch "kafka_producer_send_mean"       "rate(spring_kafka_producer_record_send_seconds_sum[1m]) / rate(spring_kafka_producer_record_send_seconds_count[1m])"
fetch "kafka_producer_send_p95"        "histogram_quantile(0.95, rate(spring_kafka_producer_record_send_seconds_bucket[1m]))"
fetch "kafka_producer_send_p99"        "histogram_quantile(0.99, rate(spring_kafka_producer_record_send_seconds_bucket[1m]))"
fetch "kafka_producer_error_pct"       "(rate(spring_kafka_producer_record_send_failed_total[1m]) / (rate(spring_kafka_producer_record_send_total[1m]) + rate(spring_kafka_producer_record_send_failed_total[1m]))) * 100"
fetch "kafka_producer_by_topic"        "sum(rate(spring_kafka_producer_record_send_total[1m])) by (topic)"
fetch "kafka_consumer_fetched"         "rate(spring_kafka_consumer_record_fetch_total[1m])"
fetch "kafka_consumer_fetched_topic"   "rate(spring_kafka_consumer_record_fetch_total[1m])"
fetch "kafka_consumer_lag"             "spring_kafka_consumer_lag_sum"
fetch "kafka_consumer_commit_failures" "spring_kafka_consumer_commit_failures_total"
fetch "kafka_consumer_errors"          "rate(spring_kafka_consumer_errors_total[1m])"
fetch "kafka_template_success"         "rate(spring_kafka_template_seconds_count{result='success'}[1m])"
fetch "kafka_template_failures"        "rate(spring_kafka_template_seconds_count{result!='success'}[1m])"
fetch "kafka_template_latency_p95"     "histogram_quantile(0.95, rate(spring_kafka_template_seconds_bucket[1m]))"
fetch "kafka_template_latency_p99"     "histogram_quantile(0.99, rate(spring_kafka_template_seconds_bucket[1m]))"
fetch "kafka_template_total"           "spring_kafka_template_seconds_count"
fetch "kafka_template_max_send_time"   "spring_kafka_template_seconds_max"
fetch "kafka_messages_by_topic"        "sum(rate(spring_kafka_producer_record_send_total[1m])) by (topic)"
fetch "kafka_throughput_by_topic"      "sum(rate(spring_kafka_producer_record_send_total[1m])) by (topic)"

# Additional Kafka queries for newer metric families / labels.
# Keep legacy queries above for backward compatibility across projects.
fetch "kafka_client_record_send_total"         "kafka_producer_record_send_total"
fetch "kafka_client_record_send_rate"          "kafka_producer_record_send_rate"
fetch "kafka_client_record_error_total"        "kafka_producer_record_error_total"
fetch "kafka_client_record_error_rate"         "kafka_producer_record_error_rate"
fetch "kafka_client_request_latency_avg_ms"    "kafka_producer_request_latency_avg"
fetch "kafka_client_request_latency_max_ms"    "kafka_producer_request_latency_max"
fetch "kafka_client_outgoing_byte_rate"        "kafka_producer_outgoing_byte_rate"
fetch "kafka_client_incoming_byte_rate"        "kafka_producer_incoming_byte_rate"
fetch "kafka_client_network_io_rate"           "kafka_producer_network_io_rate"
fetch "kafka_client_requests_in_flight"        "kafka_producer_requests_in_flight"
fetch "kafka_client_batch_size_avg"            "kafka_producer_batch_size_avg"
fetch "kafka_client_record_size_avg"           "kafka_producer_record_size_avg"
fetch "kafka_client_compression_rate_avg"      "kafka_producer_compression_rate_avg"
fetch "kafka_client_by_node_request_rate"      "kafka_producer_node_request_rate"
fetch "kafka_client_by_node_outgoing_byte_rate" "kafka_producer_node_outgoing_byte_rate"
fetch "kafka_client_consumer_lag"              "kafka_consumer_records_lag"
fetch "kafka_client_consumer_commit_failed_total" "kafka_consumer_commit_failed_total"
fetch "kafka_client_consumer_failed_rebalance_rate" "rate(kafka_consumer_failed_rebalance_total[1m])"
fetch "kafka_template_success_error_none"      "rate(spring_kafka_template_seconds_count{error='none'}[1m])"
fetch "kafka_template_failures_error_not_none" "rate(spring_kafka_template_seconds_count{error!='none'}[1m])"
fetch "kafka_template_messages_by_destination" "sum(rate(spring_kafka_template_seconds_count[1m])) by (messaging_destination_name)"
fetch "kafka_template_latency_mean_by_destination_s" "sum(rate(spring_kafka_template_seconds_sum[1m])) by (messaging_destination_name) / clamp_min(sum(rate(spring_kafka_template_seconds_count[1m])) by (messaging_destination_name), 1)"
fetch "kafka_template_latency_max_by_destination_s"  "max(spring_kafka_template_seconds_max) by (messaging_destination_name)"
fetch "kafka_template_error_percentage"        "100 * (sum(rate(spring_kafka_template_seconds_count{error!='none'}[1m])) or vector(0)) / clamp_min(sum(rate(spring_kafka_template_seconds_count[1m])), 1)"

# ── 6. PDMS CUSTOM METRICS ────────────────────────────────────────────────────
echo ""
echo "[6/7] PDMS custom metrics..."
fetch "pdms_consent_grant"             "rate(pdms_consent_grant_total[1m])"
fetch "pdms_consent_revoke"            "rate(pdms_consent_revoke_total[1m])"
fetch "pdms_data_export_requests"      "rate(pdms_data_export_request_total[1m])"
fetch "pdms_data_deletion_requests"    "rate(pdms_data_deletion_request_total[1m])"
fetch "pdms_dsar_in_progress"          "pdms_dsar_requests_in_progress"
fetch "pdms_deletion_in_progress"      "pdms_data_deletion_requests_in_progress"
fetch "pdms_dsar_processing_p95"       "histogram_quantile(0.95, rate(pdms_dsar_processing_seconds_bucket[1m]))"
fetch "pdms_deletion_processing_p95"   "histogram_quantile(0.95, rate(pdms_data_deletion_processing_seconds_bucket[1m]))"
fetch "pdms_privacy_policy_acceptance" "rate(pdms_privacy_policy_acceptance_total[1m])"

# ── 7. NODE / SYSTEM METRICS ──────────────────────────────────────────────────
echo ""
echo "[7/7] Node/system metrics..."
fetch "node_memory_total"         "node_memory_MemTotal_bytes"
fetch "node_memory_available"     "node_memory_MemAvailable_bytes"
fetch "node_memory_free"          "node_memory_MemFree_bytes"
fetch "node_memory_cached"        "node_memory_Cached_bytes"
fetch "node_memory_buffers"       "node_memory_Buffers_bytes"
fetch "node_cpu_usage"            "rate(node_cpu_seconds_total{mode!='idle'}[1m])"
fetch "node_cpu_idle"             "rate(node_cpu_seconds_total{mode='idle'}[1m])"
fetch "node_load1"                "node_load1"
fetch "node_load5"                "node_load5"
fetch "node_load15"               "node_load15"
fetch "node_disk_read_ops"        "rate(node_disk_reads_completed_total[1m])"
fetch "node_disk_write_ops"       "rate(node_disk_writes_completed_total[1m])"
fetch "node_disk_read_bytes"      "rate(node_disk_read_bytes_total[1m])"
fetch "node_disk_write_bytes"     "rate(node_disk_written_bytes_total[1m])"
fetch "node_disk_io_time"         "rate(node_disk_io_time_seconds_total[1m])"
fetch "node_network_in"           "rate(node_network_receive_bytes_total[1m])"
fetch "node_network_out"          "rate(node_network_transmit_bytes_total[1m])"
fetch "node_network_in_errors"    "rate(node_network_receive_errs_total[1m])"
fetch "node_network_out_errors"   "rate(node_network_transmit_errs_total[1m])"
fetch "node_filesystem_avail"     "node_filesystem_avail_bytes"
fetch "node_filesystem_size"      "node_filesystem_size_bytes"
fetch "container_cpu_usage"       "rate(container_cpu_usage_seconds_total[1m])"
fetch "container_memory_usage"    "container_memory_usage_bytes"
fetch "container_network_in"      "rate(container_network_receive_bytes_total[1m])"
fetch "container_network_out"     "rate(container_network_transmit_bytes_total[1m])"

echo ""
echo "============================================="
echo " DONE"
echo " Output : $OUTDIR"
echo " Files  : $(ls "$OUTDIR" | wc -l | tr -d ' ') collected"
echo "============================================="
