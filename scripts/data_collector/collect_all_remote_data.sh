#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./collect_all_remote_data.sh <output_folder_name> [variant] [scenario] [start_ts] [end_ts]
# Example:
#   ./collect_all_remote_data.sh monolith_1_write monolith mixed 1745760000 1745763600
#   ./collect_all_remote_data.sh faas_1_write faas write-heavy 1745760000 1745763600
#   ./collect_all_remote_data.sh decentralized_1_mix decentralized mixed 1745760000 1745763600
#   For variant=decentralized, also collects Besu artifacts from the DB/Besu VM (see collect_besu_data.sh).
#   Host CPU/RAM (under <output>/hostMetrics/): per-VM bundle via collect_vm_host_metrics.sh (SSH to each host).
#   True "last hour" needs sysstat (sar) data in /var/log/sysstat on each Linux VM; otherwise see README in each bundle.
#
# Kafka topic inventory (under <output>/dbAndKafka/):
#   - SSH to KAFKABAT_HOST (default: Host "kafkabat" from ~/.ssh/config) runs export_kafka_topic_inventory.sh local.
#   - On the machine running this script: export_kafka_topic_inventory.sh cloud (override CLOUD_BOOTSTRAP / CLOUD_COMMAND_CONFIG as needed).
#   - KAFKABAT_KEY defaults to DBandKafka.pem; set KAFKABAT_KEY / KAFKABAT_HOST to override.

# SSH hardening: a half-open TCP stream on the host-metrics transfer used to hang
# forever (observed 36 min on 2026-08-13). Keepalives make a dead peer fail fast.
SSH_KEEPALIVE_OPTS=(
  -o ConnectTimeout=20
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=8
)

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <output_folder_name> [variant] [scenario] [start_ts] [end_ts]"
  exit 1
fi

OUTPUT_ROOT_NAME="$1"
VARIANT="${2:-monolith}"
SCENARIO="${3:-mixed}"
START_TS="${4:-}"
END_TS="${5:-}"

case "${VARIANT}" in
  monolith|microservices|microservices-async|faas|decentralized)
    ;;
  *)
    echo "Unsupported variant: ${VARIANT}"
    echo "Supported values: monolith, microservices, microservices-async, faas, decentralized"
    exit 1
    ;;
esac

if [[ -z "${START_TS}" && -z "${END_TS}" ]]; then
  NOW_UTC="$(date -u +%s)"
  START_TS="$((NOW_UTC - 3600))"
  END_TS="${NOW_UTC}"
  AUTO_RANGE="1"
elif [[ -z "${START_TS}" || -z "${END_TS}" ]]; then
  echo "Usage: $0 <output_folder_name> [variant] [scenario] [start_ts] [end_ts]"
  echo "Hint: pass both start_ts and end_ts, or neither to auto-use the last 60 minutes (UTC)."
  exit 1
else
  AUTO_RANGE="0"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_ROOT="${BASE_DIR}/${OUTPUT_ROOT_NAME}"
APP_DIR="${OUTPUT_ROOT}/app"
DB_KAFKA_DIR="${OUTPUT_ROOT}/dbAndKafka"
BESU_DIR="${OUTPUT_ROOT}/besu"
JMETER_DIR="${OUTPUT_ROOT}/jmeter"
PROM_DIR="${OUTPUT_ROOT}/prometheus"
RUN_LOG="${OUTPUT_ROOT}/run.log"
BESU_LOCAL_COLLECTOR_PATH="${BESU_LOCAL_COLLECTOR_PATH:-${SCRIPT_DIR}/collect_besu_data.sh}"
BESU_SYNC_SCRIPT="${BESU_SYNC_SCRIPT:-1}"
HOST_METRICS_DIR="${OUTPUT_ROOT}/hostMetrics"
HOST_VM_METRICS_ENABLE="${HOST_VM_METRICS_ENABLE:-1}"
HOST_VM_SYNC="${HOST_VM_SYNC:-1}"
HOST_VM_LOCAL_COLLECTOR_PATH="${HOST_VM_LOCAL_COLLECTOR_PATH:-${SCRIPT_DIR}/collect_vm_host_metrics.sh}"

KEYS_DIR="${KEYS_DIR:-$HOME/.ssh/pdms-keys}"

APP_KEY="${KEYS_DIR}/PDMS_key_sec.pem"
DB_KEY="${KEYS_DIR}/KafkaAndDB_sec.pem"
JMETER_KEY="${KEYS_DIR}/Jmeter_key_sec.pem"
PROM_KEY="${KEYS_DIR}/Observability_sec.pem"
BESU_KEY="${BESU_KEY:-${KEYS_DIR}/besu.pem}"

APP_HOST="<ssh-user>@<app-host>"
DB_HOST="<ssh-user>@<db-host>"
JMETER_HOST="<ssh-user>@<jmeter-host>"
PROM_HOST="<ssh-user>@<observability-host>"
# Besu moved to its own VM (2026-08) and runs as <ssh-user>; <ssh-user> has no
# docker.sock permission there, so collection silently failed under the old user.
BESU_HOST="${BESU_HOST:-<ssh-user>@<besu-host>}"
PROM_REMOTE_COLLECTOR_PATH="${PROM_REMOTE_COLLECTOR_PATH:-\$HOME/collect_prometheus_metrics.sh}"
PROM_LOCAL_COLLECTOR_PATH="${PROM_LOCAL_COLLECTOR_PATH:-${REPO_ROOT}/data_view/collect_prometheus_metrics.sh}"
PROM_LOCAL_DASHBOARD_PATH="${PROM_LOCAL_DASHBOARD_PATH:-${REPO_ROOT}/grafana/dashboards/pdms-dashboard.json}"
PROM_REMOTE_DASHBOARD_PATH="${PROM_REMOTE_DASHBOARD_PATH:-\$HOME/pdms-dashboard.json}"
PROM_SYNC_COLLECTOR="${PROM_SYNC_COLLECTOR:-1}"
PROMETHEUS_DATA_DIR_REMOTE="${PROMETHEUS_DATA_DIR_REMOTE:-/var/lib/prometheus}"
PROM_ENABLE_TSDB_SNAPSHOT="${PROM_ENABLE_TSDB_SNAPSHOT:-1}"
PROM_ENABLE_TSDB_DIR_BACKUP="${PROM_ENABLE_TSDB_DIR_BACKUP:-1}"
PROM_TSDB_MIN_BYTES="${PROM_TSDB_MIN_BYTES:-131072}"
PROM_REQUIRE_TSDB_BACKUP="${PROM_REQUIRE_TSDB_BACKUP:-1}"
# Host with PDMS local Kafka (SSH Host entry or user@host). Inventory "local" runs there.
# Defaults to DB host to avoid dependency on a local SSH alias.
KAFKABAT_HOST="${KAFKABAT_HOST:-${DB_HOST}}"
KAFKABAT_KEY="${KAFKABAT_KEY:-${DB_KEY}}"

mkdir -p "${APP_DIR}" "${DB_KAFKA_DIR}" "${PROM_DIR}"
touch "${RUN_LOG}"

log() {
  local level="$1"
  local part="$2"
  local message="$3"
  local ts
  ts="$(date '+%Y-%m-%d %H:%M:%S')"
  printf "[%s] [%s] [%s] %s\n" "${ts}" "${level}" "${part}" "${message}" | tee -a "${RUN_LOG}"
}

require_file() {
  local file_path="$1"
  local label="$2"
  if [[ ! -f "${file_path}" ]]; then
    log "ERROR" "INIT" "${label} not found: ${file_path}"
    exit 1
  fi
}

run_remote_and_collect() {
  local part_name="$1"
  local key_file="$2"
  local host="$3"
  local remote_command="$4"
  local local_destination="$5"
  local section_log="${local_destination}/${part_name}.log"
  local tar_tmp
  tar_tmp="$(mktemp "${local_destination}/.${part_name}.XXXXXX.tgz")"

  log "INFO" "${part_name}" "Starting remote collection on ${host}"

  if ssh -i "${key_file}" -o StrictHostKeyChecking=accept-new "${SSH_KEEPALIVE_OPTS[@]}" "${host}" "${remote_command}" > "${tar_tmp}" 2> "${section_log}"; then
    if tar -xzf "${tar_tmp}" -C "${local_destination}" >> "${section_log}" 2>&1; then
      rm -f "${tar_tmp}"
      log "INFO" "${part_name}" "Collection completed and extracted to ${local_destination}"
    else
      rm -f "${tar_tmp}"
      log "ERROR" "${part_name}" "Failed to extract archive. Check ${section_log}"
      return 1
    fi
  else
    rm -f "${tar_tmp}"
    log "ERROR" "${part_name}" "Remote command failed. Check ${section_log}"
    return 1
  fi
}

run_remote_and_zip() {
  local part_name="$1"
  local key_file="$2"
  local host="$3"
  local remote_command="$4"
  local zip_path="$5"
  local section_log="$6"
  local tar_tmp
  local extract_tmp
  tar_tmp="$(mktemp "${OUTPUT_ROOT}/.${part_name}.XXXXXX.tgz")"
  extract_tmp="$(mktemp -d "${OUTPUT_ROOT}/.${part_name}.XXXXXX")"

  log "INFO" "${part_name}" "Starting remote collection on ${host}"

  if ssh -i "${key_file}" -o StrictHostKeyChecking=accept-new "${SSH_KEEPALIVE_OPTS[@]}" "${host}" "${remote_command}" > "${tar_tmp}" 2> "${section_log}"; then
    if tar -xzf "${tar_tmp}" -C "${extract_tmp}" >> "${section_log}" 2>&1; then
      rm -f "${tar_tmp}"
      rm -f "${zip_path}"
      if (cd "${extract_tmp}" && zip -qr "${zip_path}" .) >> "${section_log}" 2>&1; then
        rm -rf "${extract_tmp}"
        log "INFO" "${part_name}" "Collection completed and packaged at ${zip_path}"
      else
        rm -rf "${extract_tmp}"
        log "ERROR" "${part_name}" "Failed to create ZIP archive. Check ${section_log}"
        return 1
      fi
    else
      rm -f "${tar_tmp}"
      rm -rf "${extract_tmp}"
      log "ERROR" "${part_name}" "Failed to extract archive. Check ${section_log}"
      return 1
    fi
  else
    rm -f "${tar_tmp}"
    rm -rf "${extract_tmp}"
    log "ERROR" "${part_name}" "Remote command failed. Check ${section_log}"
    return 1
  fi
}

require_file "${APP_KEY}" "App SSH key"
require_file "${DB_KEY}" "DB/Kafka SSH key"
require_file "${KAFKABAT_KEY}" "Kafkabat SSH key"
require_file "${JMETER_KEY}" "JMeter SSH key"
require_file "${PROM_KEY}" "Prometheus SSH key"
require_file "${PROM_LOCAL_COLLECTOR_PATH}" "Local Prometheus collector script"
require_file "${PROM_LOCAL_DASHBOARD_PATH}" "Local dashboard JSON for Prometheus query export"
if ! command -v zip >/dev/null 2>&1; then
  log "ERROR" "INIT" "'zip' command not found on collector machine. Install zip to package JMeter artifacts."
  exit 1
fi

sync_prom_collector() {
  if [[ "${PROM_SYNC_COLLECTOR}" != "1" ]]; then
    log "INFO" "prometheus" "Skipping remote collector sync (PROM_SYNC_COLLECTOR=${PROM_SYNC_COLLECTOR})"
    return 0
  fi

  log "INFO" "prometheus" "Syncing local collector to remote host ${PROM_HOST}"
  if scp -i "${PROM_KEY}" -o StrictHostKeyChecking=accept-new "${PROM_LOCAL_COLLECTOR_PATH}" "${PROM_HOST}:~/collect_prometheus_metrics.sh" >>"${RUN_LOG}" 2>&1; then
    log "INFO" "prometheus" "Remote collector updated from ${PROM_LOCAL_COLLECTOR_PATH}"
  else
    log "ERROR" "prometheus" "Failed to sync collector to remote host"
    return 1
  fi

  if scp -i "${PROM_KEY}" -o StrictHostKeyChecking=accept-new "${PROM_LOCAL_DASHBOARD_PATH}" "${PROM_HOST}:~/pdms-dashboard.json" >>"${RUN_LOG}" 2>&1; then
    log "INFO" "prometheus" "Remote dashboard JSON updated from ${PROM_LOCAL_DASHBOARD_PATH}"
  else
    log "ERROR" "prometheus" "Failed to sync dashboard JSON to remote host"
    return 1
  fi
}

sync_besu_collector() {
  if [[ "${BESU_SYNC_SCRIPT}" != "1" ]]; then
    log "INFO" "besu" "Skipping Besu collector sync (BESU_SYNC_SCRIPT=${BESU_SYNC_SCRIPT})"
    return 0
  fi
  log "INFO" "besu" "Syncing collect_besu_data.sh to ${BESU_HOST}"
  if scp -i "${BESU_KEY}" -o StrictHostKeyChecking=accept-new "${BESU_LOCAL_COLLECTOR_PATH}" "${BESU_HOST}:~/collect_besu_data.sh" >>"${RUN_LOG}" 2>&1; then
    log "INFO" "besu" "Remote Besu collector updated from ${BESU_LOCAL_COLLECTOR_PATH}"
  else
    log "ERROR" "besu" "Failed to sync collect_besu_data.sh to ${BESU_HOST}"
    return 1
  fi
}

make_host_metrics_remote_cmd() {
  local tag="$1"
  cat <<EOF
bash -lc '
set -euo pipefail
cd "\$HOME"
if [[ ! -f ./collect_vm_host_metrics.sh ]]; then
  echo "collect_vm_host_metrics.sh not in HOME (sync failed?)" >&2
  exit 1
fi
chmod +x ./collect_vm_host_metrics.sh
./collect_vm_host_metrics.sh ${tag} >/dev/null
artifact=\$(ls -td host_metrics_${tag}_* 2>/dev/null | awk "NR==1{print; exit}")
if [[ -z "\${artifact}" ]]; then
  echo "No host metrics artifact" >&2
  exit 1
fi
tar -czf - "\${artifact}"
'
EOF
}

sync_vm_host_collector() {
  if [[ "${HOST_VM_METRICS_ENABLE}" != "1" ]]; then
    log "INFO" "hostMetrics" "Skipping VM host metrics (HOST_VM_METRICS_ENABLE=${HOST_VM_METRICS_ENABLE})"
    return 0
  fi
  if [[ "${HOST_VM_SYNC}" != "1" ]]; then
    log "INFO" "hostMetrics" "Skipping VM metrics script sync (HOST_VM_SYNC=${HOST_VM_SYNC})"
    return 0
  fi
  log "INFO" "hostMetrics" "Syncing collect_vm_host_metrics.sh to app, db, jmeter, prometheus hosts"
  if scp -i "${APP_KEY}" -o StrictHostKeyChecking=accept-new "${HOST_VM_LOCAL_COLLECTOR_PATH}" "${APP_HOST}:~/collect_vm_host_metrics.sh" >>"${RUN_LOG}" 2>&1 \
    && scp -i "${DB_KEY}" -o StrictHostKeyChecking=accept-new "${HOST_VM_LOCAL_COLLECTOR_PATH}" "${DB_HOST}:~/collect_vm_host_metrics.sh" >>"${RUN_LOG}" 2>&1 \
    && scp -i "${JMETER_KEY}" -o StrictHostKeyChecking=accept-new "${HOST_VM_LOCAL_COLLECTOR_PATH}" "${JMETER_HOST}:~/collect_vm_host_metrics.sh" >>"${RUN_LOG}" 2>&1 \
    && scp -i "${PROM_KEY}" -o StrictHostKeyChecking=accept-new "${HOST_VM_LOCAL_COLLECTOR_PATH}" "${PROM_HOST}:~/collect_vm_host_metrics.sh" >>"${RUN_LOG}" 2>&1 \
    && { [[ "${VARIANT}" != "decentralized" ]] || scp -i "${BESU_KEY}" -o StrictHostKeyChecking=accept-new "${HOST_VM_LOCAL_COLLECTOR_PATH}" "${BESU_HOST}:~/collect_vm_host_metrics.sh" >>"${RUN_LOG}" 2>&1; }; then
    log "INFO" "hostMetrics" "Remote collect_vm_host_metrics.sh updated on all four hosts"
  else
    log "ERROR" "hostMetrics" "Failed to sync collect_vm_host_metrics.sh to one or more hosts"
    return 1
  fi
}

APP_REMOTE_CMD=$(cat <<EOF
bash -lc '
set -euo pipefail
cd "\$HOME/PDMS"
./collect_app_logs.sh "${VARIANT}" >/dev/null
artifact=\$(ls -t app_logs_${VARIANT}_*.txt 2>/dev/null | awk "NR==1 {print; exit}")
if [[ -z "\${artifact}" ]]; then
  echo "No app artifact found" >&2
  exit 1
fi
tar -czf - "\${artifact}"
'
EOF
)

DB_REMOTE_CMD=$(cat <<EOF
bash -lc '
set -euo pipefail
cd "\$HOME"
./collect_db_kafka.sh "${VARIANT}" "${SCENARIO}" >/dev/null
artifact=\$(ls -td db_kafka_${VARIANT}_${SCENARIO}_* 2>/dev/null | awk "NR==1 {print; exit}")
if [[ -z "\${artifact}" ]]; then
  echo "No db/kafka artifact found" >&2
  exit 1
fi
tar -czf - "\${artifact}"
'
EOF
)

JMETER_REMOTE_CMD=$(cat <<EOF
bash -lc '
set -euo pipefail
cd "\$HOME"
artifact="${VARIANT}_${SCENARIO}_\$(date +%Y%m%d_%H%M%S)"
mkdir -p "\${artifact}"

copied=0
if compgen -G "\$HOME/jmeter/scripts/results/*.jtl" > /dev/null; then
  cp "\$HOME"/jmeter/scripts/results/*.jtl "\${artifact}/"
  copied=1
elif compgen -G "\$HOME/jmeter/results/*.jtl" > /dev/null; then
  cp "\$HOME"/jmeter/results/*.jtl "\${artifact}/"
  copied=1
fi

if [[ "\${copied}" -ne 1 ]]; then
  echo "No JMeter .jtl files found under ~/jmeter/scripts/results or ~/jmeter/results" >&2
  exit 1
fi

if [[ -z "\${artifact}" ]]; then
  echo "No jmeter artifact found" >&2
  exit 1
fi
tar -czf - "\${artifact}"
'
EOF
)

KAFKABAT_KAFKA_INV_CMD=$(cat <<'EOF'
bash -lc '
set -euo pipefail
cd "$HOME"
inv_out="kafka_topic_inventory_kafkabat_local_$(date +%Y%m%d_%H%M%S)"
./export_kafka_topic_inventory.sh local "${inv_out}"
tar -czf - "${inv_out}"
'
EOF
)

PROM_REMOTE_CMD=$(cat <<EOF
bash -lc '
set -euo pipefail
cd "\$HOME"
collector_path="${PROM_REMOTE_COLLECTOR_PATH}"
if [[ ! -f "\${collector_path}" ]]; then
  echo "Remote collector not found: \${collector_path}" >&2
  exit 1
fi
chmod +x "\${collector_path}" || true
collector_log="/tmp/collect_prometheus_metrics.log"
if ! ENABLE_TSDB_SNAPSHOT="${PROM_ENABLE_TSDB_SNAPSHOT}" ENABLE_TSDB_DIR_BACKUP="${PROM_ENABLE_TSDB_DIR_BACKUP}" TSDB_MIN_BYTES="${PROM_TSDB_MIN_BYTES}" PROMETHEUS_DATA_DIR="${PROMETHEUS_DATA_DIR_REMOTE}" DASHBOARD_JSON="${PROM_REMOTE_DASHBOARD_PATH}" sudo -n -E "\${collector_path}" "${START_TS}" "${END_TS}" 15s "http://127.0.0.1:9090" >"\${collector_log}" 2>&1; then
  echo "Remote Prometheus collector failed. Log:" >&2
  echo "----- first 80 lines -----" >&2
  sed -n "1,80p" "\${collector_log}" >&2 || true
  echo "----- last 80 lines ------" >&2
  tail -n 80 "\${collector_log}" >&2 || true
  exit 1
fi
echo "----- first 40 lines -----" >&2
sed -n "1,40p" "\${collector_log}" >&2 || true
echo "----- last 40 lines ------" >&2
tail -n 40 "\${collector_log}" >&2 || true
artifact=\$(ls -td prometheus_metrics_* 2>/dev/null | awk "NR==1 {print; exit}")
if [[ -z "\${artifact}" ]]; then
  echo "No prometheus artifact found after running remote collector" >&2
  exit 1
fi
if [[ "${PROM_REQUIRE_TSDB_BACKUP}" == "1" && "${PROM_ENABLE_TSDB_SNAPSHOT}" == "1" ]]; then
  if [[ ! -f "\${artifact}/tsdb_snapshot/tsdb_data_backup.tar.gz" ]] && [[ ! -d "\${artifact}/tsdb_snapshot" ]]; then
    echo "TSDB backup/snapshot missing in artifact: \${artifact}" >&2
    exit 1
  fi
fi
tar -czf - "\${artifact}"
'
EOF
)

BESU_REMOTE_CMD=$(cat <<EOF
bash -lc '
set -euo pipefail
cd "\$HOME"
if [[ ! -f ./collect_besu_data.sh ]]; then
  echo "collect_besu_data.sh not found in HOME (sync failed?)" >&2
  exit 1
fi
chmod +x ./collect_besu_data.sh
./collect_besu_data.sh "${VARIANT}" >/dev/null
artifact=\$(ls -td besu_data_${VARIANT}_* 2>/dev/null | awk "NR==1 {print; exit}")
if [[ -z "\${artifact}" ]]; then
  echo "No besu artifact found" >&2
  exit 1
fi
tar -czf - "\${artifact}"
'
EOF
)

HOST_METRICS_APP_CMD="$(make_host_metrics_remote_cmd app)"
HOST_METRICS_DB_CMD="$(make_host_metrics_remote_cmd db)"
HOST_METRICS_JMETER_CMD="$(make_host_metrics_remote_cmd jmeter)"
HOST_METRICS_PROM_CMD="$(make_host_metrics_remote_cmd prom)"
HOST_METRICS_BESU_CMD="$(make_host_metrics_remote_cmd besu)"

FAILURES=0

if [[ "${AUTO_RANGE}" == "1" ]]; then
  log "INFO" "prometheus" "No range provided. Using last 60 minutes UTC: ${START_TS} ($(date -u -r "${START_TS}" +"%Y-%m-%dT%H:%M:%SZ")) -> ${END_TS} ($(date -u -r "${END_TS}" +"%Y-%m-%dT%H:%M:%SZ"))"
else
  log "INFO" "prometheus" "Using explicit range: ${START_TS} -> ${END_TS}"
fi

run_remote_and_collect "app" "${APP_KEY}" "${APP_HOST}" "${APP_REMOTE_CMD}" "${APP_DIR}" || FAILURES=$((FAILURES + 1))
run_remote_and_collect "dbAndKafka" "${DB_KEY}" "${DB_HOST}" "${DB_REMOTE_CMD}" "${DB_KAFKA_DIR}" || FAILURES=$((FAILURES + 1))

if [[ "${VARIANT}" == "decentralized" ]]; then
  mkdir -p "${BESU_DIR}"
  require_file "${BESU_LOCAL_COLLECTOR_PATH}" "Local Besu collector script"
  sync_besu_collector || FAILURES=$((FAILURES + 1))
  run_remote_and_collect "besu" "${BESU_KEY}" "${BESU_HOST}" "${BESU_REMOTE_CMD}" "${BESU_DIR}" || FAILURES=$((FAILURES + 1))
fi

# kafkaKafkabatLocal intentionally skipped.
# Only cloud topic inventory is collected.

# This machine: topic inventory against cloud cluster (default CLOUD_BOOTSTRAP in export_kafka_topic_inventory.sh). Needs Kafka CLI on PATH and broker reachability.
KAFKA_TOPIC_INV_DIR="${DB_KAFKA_DIR}/kafka_topic_inventory_cloud_$(date +%Y%m%d_%H%M%S)"
log "INFO" "kafkaInventoryCloud" "Running export_kafka_topic_inventory.sh cloud -> ${KAFKA_TOPIC_INV_DIR}"
if [[ ! -f "${SCRIPT_DIR}/export_kafka_topic_inventory.sh" ]]; then
  log "ERROR" "kafkaInventoryCloud" "Script not found: ${SCRIPT_DIR}/export_kafka_topic_inventory.sh"
  FAILURES=$((FAILURES + 1))
else
  if (cd "${SCRIPT_DIR}" && bash ./export_kafka_topic_inventory.sh cloud "${KAFKA_TOPIC_INV_DIR}") >>"${RUN_LOG}" 2>&1; then
    log "INFO" "kafkaInventoryCloud" "Saved topic_inventory_cloud.csv under ${KAFKA_TOPIC_INV_DIR}"
  else
    log "WARN" "kafkaInventoryCloud" "export_kafka_topic_inventory.sh cloud failed, continuing (Kafka CLI on PATH? CLOUD_BOOTSTRAP / CLOUD_COMMAND_CONFIG? Network to broker?). See ${KAFKA_TOPIC_INV_DIR}/export.log if created."
  fi
fi

run_remote_and_zip "jmeter" "${JMETER_KEY}" "${JMETER_HOST}" "${JMETER_REMOTE_CMD}" "${OUTPUT_ROOT}/jmeter.zip" "${OUTPUT_ROOT}/jmeter.log" || FAILURES=$((FAILURES + 1))
sync_prom_collector || FAILURES=$((FAILURES + 1))
run_remote_and_collect "prometheus" "${PROM_KEY}" "${PROM_HOST}" "${PROM_REMOTE_CMD}" "${PROM_DIR}" || FAILURES=$((FAILURES + 1))

if [[ "${HOST_VM_METRICS_ENABLE}" == "1" ]]; then
  require_file "${HOST_VM_LOCAL_COLLECTOR_PATH}" "VM host metrics collector script"
  mkdir -p "${HOST_METRICS_DIR}"
  sync_vm_host_collector || FAILURES=$((FAILURES + 1))
  run_remote_and_collect "hostMetrics_app" "${APP_KEY}" "${APP_HOST}" "${HOST_METRICS_APP_CMD}" "${HOST_METRICS_DIR}" || FAILURES=$((FAILURES + 1))
  run_remote_and_collect "hostMetrics_db" "${DB_KEY}" "${DB_HOST}" "${HOST_METRICS_DB_CMD}" "${HOST_METRICS_DIR}" || FAILURES=$((FAILURES + 1))
  run_remote_and_collect "hostMetrics_jmeter" "${JMETER_KEY}" "${JMETER_HOST}" "${HOST_METRICS_JMETER_CMD}" "${HOST_METRICS_DIR}" || FAILURES=$((FAILURES + 1))
  run_remote_and_collect "hostMetrics_prom" "${PROM_KEY}" "${PROM_HOST}" "${HOST_METRICS_PROM_CMD}" "${HOST_METRICS_DIR}" || FAILURES=$((FAILURES + 1))
  if [[ "${VARIANT}" == "decentralized" ]]; then
    # Besu has its own VM from 2026-08; without this its resource cost is unmeasurable.
    run_remote_and_collect "hostMetrics_besu" "${BESU_KEY}" "${BESU_HOST}" "${HOST_METRICS_BESU_CMD}" "${HOST_METRICS_DIR}" || FAILURES=$((FAILURES + 1))
  fi
fi

if [[ "${FAILURES}" -gt 0 ]]; then
  log "ERROR" "FINAL" "Completed with ${FAILURES} failed section(s)."
  exit 1
fi

log "INFO" "FINAL" "Completed successfully. Artifacts available at ${OUTPUT_ROOT}"
