#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   ./restart_all_services.sh <app_name>
# Example:
#   ./restart_all_services.sh monolith

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <app_name>"
  exit 1
fi

APP_NAME="$1"
APP_DIR="pdms-${APP_NAME}"
APP_IMAGE="pdms-${APP_NAME}:latest"
BUILD_STEPS=$'cd '"${APP_DIR}"$'\ndocker build -t '"${APP_IMAGE}"$' .\ncd ..'
COMPOSE_FILE="docker-compose.${APP_NAME}.yml"

case "${APP_NAME}" in
  monolith)
    APP_DIR="pdms-monolith"
    APP_IMAGE="pdms-monolith:latest"
    BUILD_STEPS=$'cd pdms-monolith\ndocker build -t pdms-monolith:latest .\ncd ..'
    ;;
  microservices)
    BUILD_STEPS=$'cd pdms-microservice-person\ndocker build -t pdms-microservice-person:latest .\ncd ..\ncd pdms-microservice-snapshot\ndocker build -t pdms-microservice-snapshot:latest .\ncd ..\ncd pdms-microservice-consent\ndocker build -t pdms-microservice-consent:latest .\ncd ..'
    ;;
  microservices-async)
    BUILD_STEPS=$'cd pdms-microservice-person-async\ndocker build -t pdms-microservice-person-async:latest .\ncd ..\ncd pdms-microservice-snapshot-async\ndocker build -t pdms-microservice-snapshot-async:latest .\ncd ..\ncd pdms-microservice-consent-async\ndocker build -t pdms-microservice-consent-async:latest .\ncd ..'
    ;;
  faas)
    APP_DIR="pdms-faas"
    APP_IMAGE="pdms-faas:latest"
    BUILD_STEPS=$'cd pdms-faas\ndocker build -t pdms-faas:latest .\ncd ..'
    COMPOSE_FILE="docker-compose.faas.remote.yml"
    ;;
  decentralized)
    APP_DIR="pdms-decentralized"
    APP_IMAGE="pdms-hybrid-decentralized:latest"
    BUILD_STEPS=$'cd pdms-decentralized\ndocker build -t pdms-hybrid-decentralized:latest .\ncd ..'
    COMPOSE_FILE="docker-compose.decentralized.yml"
    ;;
  *)
    echo "Unsupported app_name: ${APP_NAME}"
    echo "Supported values: monolith, microservices, microservices-async, faas, decentralized"
    exit 1
    ;;
esac

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
BASE_DIR="$(pwd)"
LOG_DIR="${BASE_DIR}/restart_logs_${APP_NAME}_${TIMESTAMP}"
RUN_LOG="${LOG_DIR}/run.log"

KEYS_DIR="${KEYS_DIR:-$HOME/.ssh/pdms-keys}"
APP_KEY="${KEYS_DIR}/PDMS_key_sec.pem"
DB_KEY="${KEYS_DIR}/KafkaAndDB_sec.pem"
PROM_KEY="${KEYS_DIR}/Observability_sec.pem"
JMETER_KEY="${KEYS_DIR}/Jmeter_key_sec.pem"
BESU_KEY="${BESU_KEY:-${KEYS_DIR}/besu.pem}"

APP_HOST="<ssh-user>@<app-host>"
DB_HOST="<ssh-user>@<db-host>"
PROM_HOST="<ssh-user>@<observability-host>"
JMETER_HOST="<ssh-user>@<jmeter-host>"
# Besu VM runs as <ssh-user>: <ssh-user> has no docker.sock permission there
# and no docker-compose.besu.yml in its home, so the Besu reset silently failed.
BESU_HOST="${BESU_HOST:-<ssh-user>@<besu-host>}"

mkdir -p "${LOG_DIR}"
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

run_remote() {
  local part_name="$1"
  local key_file="$2"
  local host="$3"
  local command="$4"
  local section_log="${LOG_DIR}/${part_name}.log"

  log "INFO" "${part_name}" "Running remote commands on ${host}"
  if ssh -i "${key_file}" -o StrictHostKeyChecking=accept-new "${host}" "${command}" >> "${section_log}" 2>&1; then
    log "INFO" "${part_name}" "Completed successfully"
  else
    log "ERROR" "${part_name}" "Failed. Check ${section_log}"
    return 1
  fi
}

require_file "${APP_KEY}" "App SSH key"
require_file "${DB_KEY}" "DB/Kafka SSH key"
require_file "${PROM_KEY}" "Prometheus SSH key"
require_file "${JMETER_KEY}" "JMeter SSH key"
if [[ "${APP_NAME}" == "decentralized" ]]; then require_file "${BESU_KEY}" "Besu SSH key"; fi

APP_REMOTE_CMD=$(cat <<EOF
bash -lc '
set -euo pipefail
cd PDMS
echo "[APP] docker compose down"
docker compose -f ${COMPOSE_FILE} down -v --remove-orphans
echo "[APP] rebuild image"
${BUILD_STEPS}
echo "[APP] docker compose up"
POSTGRES_HOST=<db-host> KAFKA_HOST=<db-host> docker compose -f ${COMPOSE_FILE} up -d --remove-orphans
'
EOF
)

# DB/Kafka VM: wipe Postgres+Kafka volumes on every restart.
# For decentralized only: also reset Besu chain data (container layer) and restart Besu.
DB_REMOTE_CMD_DEFAULT=$(cat <<'EOF'
bash -lc '
set -euo pipefail
cd ~
echo "[DB_KAFKA] docker compose down"
docker compose -f docker-compose_DB_KAFKA.yml down -v
echo "[DB_KAFKA] docker compose up"
docker compose -f docker-compose_DB_KAFKA.yml up -d
'
EOF
)


# Besu moved to its own VM (2026-08). The data-layer VM now only ever handles
# Postgres + Kafka, for every variant, which is what keeps the shared data layer
# constant across all five. Besu is wiped and restarted separately, below.
DB_REMOTE_CMD="${DB_REMOTE_CMD_DEFAULT}"

# Fresh chain per run, on the dedicated Besu VM.
BESU_REMOTE_CMD=$(cat <<'EOF'
bash -lc '
set -euo pipefail
cd ~
BESU_COMPOSE_FILE=""
if [[ -f ~/docker-compose.besu.yml ]]; then
  BESU_COMPOSE_FILE=~/docker-compose.besu.yml
elif [[ -f ~/PDMS/docker-compose.besu.yml ]]; then
  BESU_COMPOSE_FILE=~/PDMS/docker-compose.besu.yml
fi
if [[ -z "${BESU_COMPOSE_FILE}" ]]; then
  echo "[BESU] ERROR: docker-compose.besu.yml not found under ~/ or ~/PDMS" >&2
  exit 1
fi
echo "[BESU] down --remove-orphans (wipe chain in container layer)"
docker compose -f "${BESU_COMPOSE_FILE}" down --remove-orphans || true
while read -r cid; do
  [[ -z "${cid}" ]] && continue
  docker rm -f "${cid}" || true
done < <(docker ps -aq --filter "name=besu" 2>/dev/null || true)
echo "[BESU] up -d --build --force-recreate (fresh chain)"
docker compose -f "${BESU_COMPOSE_FILE}" up -d --build --force-recreate
echo "[BESU] applied limits:"
docker inspect pdms-decentralized-besu --format "  NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}" 2>/dev/null || true
'
EOF
)

if [[ "${APP_NAME}" == "microservices" || "${APP_NAME}" == "microservices-async" ]]; then
  OBS_FILE_NAME="docker-compose.observability-microservices.yml"
else
  OBS_FILE_NAME="docker-compose.observability.yml"
fi

# Prometheus is wiped, not just restarted: the named prometheus_data volume is removed
# so each run starts with an empty TSDB. Without this, a collection window that
# overlaps an earlier run can pull that run's series.
PROM_REMOTE_CMD=$(cat <<EOF
bash -lc '
set -euo pipefail
OBS=""
if [[ -f ~/${OBS_FILE_NAME} ]]; then
  OBS=~/${OBS_FILE_NAME}
elif [[ -f ~/PDMS/${OBS_FILE_NAME} ]]; then
  OBS=~/PDMS/${OBS_FILE_NAME}
fi
if [[ -z "\${OBS}" ]]; then
  echo "[PROM] ERROR: ${OBS_FILE_NAME} not found under ~/ or ~/PDMS" >&2
  exit 1
fi
cd "\$(dirname "\${OBS}")"
echo "[PROM] docker compose down -v (wiping prometheus_data)"
docker compose -f "\${OBS}" down -v --remove-orphans
echo "[PROM] docker compose up -d (empty TSDB)"
docker compose -f "\${OBS}" up -d
echo "[PROM] running containers:"
docker ps --format "  {{.Names}}  {{.Status}}"
'
EOF
)
PROM_INIT_MSG="Prometheus + Grafana on Prometheus VM: compose down -v (prometheus_data wiped) then up, using ${OBS_FILE_NAME}."

# Stale collection artifacts from the PREVIOUS run. Every collector writes a timestamped
# directory/file and never removes it; collect_all_remote_data.sh then picks the newest.
# If a collection partially fails, the previous run's bundle is still there and gets
# silently attributed to this run. Clearing here means each collection sees exactly one
# bundle, produced by the run that just happened.
# All patterns are anchored on the _20<year> timestamp so nothing else can match.
CLEAN_REMOTE_CMD=$(cat <<'EOF'
bash -lc '
set -uo pipefail
cd ~
shopt -s nullglob
targets=(
  ~/host_metrics_*_20*
  ~/app_logs_*_20*.txt
  ~/PDMS/app_logs_*_20*.txt
  ~/db_kafka_*_20*
  ~/besu_data_*_20*
  ~/prometheus_metrics_20*
  ~/kafka_topic_inventory_*_20*
)
for v in monolith microservices microservices-async faas decentralized; do
  targets+=( ~/"${v}"_*_20* )
done
if [ "${#targets[@]}" -gt 0 ]; then
  echo "[CLEAN] removing ${#targets[@]} stale collection artifact(s)"
  printf "  %s\n" "${targets[@]}"
  rm -rf "${targets[@]}"
else
  echo "[CLEAN] no stale collection artifacts"
fi
for s in ~/collect_vm_host_metrics.sh ~/collect_besu_data.sh ~/collect_prometheus_metrics.sh \
         ~/collect_app_logs.sh ~/collect_db_kafka.sh ~/collect_jmeter_results.sh \
         ~/PDMS/collect_app_logs.sh ~/PDMS/collect_vm_host_metrics.sh; do
  [ -f "$s" ] && chmod +x "$s"
done
true
'
EOF
)

log "INFO" "INIT" "Starting restart flow for app=${APP_NAME}"
log "INFO" "INIT" "${PROM_INIT_MSG}"
if [[ "${APP_NAME}" == "decentralized" ]]; then
  log "INFO" "INIT" "Decentralized: Besu chain data will be wiped on DB host (expects docker-compose.besu.yml under ~/ or ~/PDMS)."
fi

FAILURES=0

run_remote "cleanApp"     "${APP_KEY}"    "${APP_HOST}"    "${CLEAN_REMOTE_CMD}" || FAILURES=$((FAILURES + 1))
run_remote "cleanDb"      "${DB_KEY}"     "${DB_HOST}"     "${CLEAN_REMOTE_CMD}" || FAILURES=$((FAILURES + 1))
run_remote "cleanJmeter"  "${JMETER_KEY}" "${JMETER_HOST}" "${CLEAN_REMOTE_CMD}" || FAILURES=$((FAILURES + 1))
run_remote "cleanProm"    "${PROM_KEY}"   "${PROM_HOST}"   "${CLEAN_REMOTE_CMD}" || FAILURES=$((FAILURES + 1))
if [[ "${APP_NAME}" == "decentralized" ]]; then
  run_remote "cleanBesu"  "${BESU_KEY}"   "${BESU_HOST}"   "${CLEAN_REMOTE_CMD}" || FAILURES=$((FAILURES + 1))
fi

run_remote "app" "${APP_KEY}" "${APP_HOST}" "${APP_REMOTE_CMD}" || FAILURES=$((FAILURES + 1))
run_remote "dbAndKafka" "${DB_KEY}" "${DB_HOST}" "${DB_REMOTE_CMD}" || FAILURES=$((FAILURES + 1))
run_remote "prometheusGrafana" "${PROM_KEY}" "${PROM_HOST}" "${PROM_REMOTE_CMD}" || FAILURES=$((FAILURES + 1))

if [[ "${APP_NAME}" == "decentralized" ]]; then
  run_remote "besu" "${BESU_KEY}" "${BESU_HOST}" "${BESU_REMOTE_CMD}" || FAILURES=$((FAILURES + 1))
fi

if [[ "${FAILURES}" -gt 0 ]]; then
  log "ERROR" "FINAL" "Finished with ${FAILURES} failed section(s)."
  exit 1
fi

log "INFO" "FINAL" "Finished successfully. Logs available at ${LOG_DIR}"
