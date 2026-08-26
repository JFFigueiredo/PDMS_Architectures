#!/usr/bin/env bash
set -euo pipefail

# Collect full Prometheus TSDB backups from the Prometheus VM and container paths.
# This script does NOT query only the last hour; it captures full on-disk data.
#
# Usage:
#   ./collect_all_remote_data_full_prometheus_db.sh <output_folder_name>
#
# Example:
#   ./collect_all_remote_data_full_prometheus_db.sh faas_full_prom_db_1

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <output_folder_name>"
  exit 1
fi

OUTPUT_ROOT_NAME="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(pwd)"
OUTPUT_ROOT="${BASE_DIR}/${OUTPUT_ROOT_NAME}"
PROM_DIR="${OUTPUT_ROOT}/prometheus_full_db"
RUN_LOG="${OUTPUT_ROOT}/run.log"

KEYS_DIR="${KEYS_DIR:-$HOME/.ssh/pdms-keys}"
PROM_KEY="${KEYS_DIR}/Observ_key.pem"
PROM_HOST="<ssh-user>@<observability-host>"

mkdir -p "${PROM_DIR}"
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
  if ssh -i "${key_file}" -o StrictHostKeyChecking=accept-new "${host}" "${remote_command}" > "${tar_tmp}" 2> "${section_log}"; then
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

require_file "${PROM_KEY}" "Prometheus SSH key"

PROM_FULL_DB_REMOTE_CMD=$(cat <<'EOF'
bash -lc '
set -euo pipefail
cd "$HOME"

artifact="prometheus_full_db_$(date +%Y%m%d_%H%M%S)"
mkdir -p "${artifact}/vm_paths" "${artifact}/container_paths"

add_readme() {
  cat > "${artifact}/README.txt" <<TXT
Full Prometheus DB collection generated from:
  - VM filesystem paths (if present)
  - Prometheus container filesystem paths (if container is running)

Captured data:
  - vm_paths/*.tar.gz
  - container_paths/*.tar.gz
  - optional tsdb_snapshot_api.json
TXT
}

backup_path() {
  local src="$1"
  local out="$2"
  if [[ -d "${src}" ]]; then
    tar -czf "${out}" -C "$(dirname "${src}")" "$(basename "${src}")"
    echo "OK ${src} -> ${out}" >&2
  fi
}

# VM-level Prometheus data directories (all-time TSDB on disk)
backup_path "/var/lib/prometheus" "${artifact}/vm_paths/var_lib_prometheus.tar.gz"
backup_path "/prometheus" "${artifact}/vm_paths/prometheus_root.tar.gz"
backup_path "/opt/prometheus/data" "${artifact}/vm_paths/opt_prometheus_data.tar.gz"
backup_path "${HOME}/prometheus" "${artifact}/vm_paths/home_prometheus.tar.gz"

# Best-effort snapshot API call for explicit TSDB snapshot metadata.
if curl -fsS -XPOST "http://127.0.0.1:9090/api/v1/admin/tsdb/snapshot?skip_head=false" \
  > "${artifact}/tsdb_snapshot_api.json" 2>/dev/null; then
  :
fi

# Container-level backups from running Prometheus container, if available.
container_id=""
if command -v docker >/dev/null 2>&1; then
  container_id="$(docker ps --filter "ancestor=prom/prometheus" -q | awk "NR==1{print; exit}")"
  if [[ -z "${container_id}" ]]; then
    container_id="$(docker ps --filter "ancestor=prom/prometheus:latest" -q | awk "NR==1{print; exit}")"
  fi
fi

if [[ -n "${container_id}" ]]; then
  if docker exec "${container_id}" sh -lc "test -d /prometheus"; then
    docker exec "${container_id}" sh -lc "tar -czf - -C / prometheus" \
      > "${artifact}/container_paths/prometheus_container_path.tar.gz"
  fi
  if docker exec "${container_id}" sh -lc "test -d /var/lib/prometheus"; then
    docker exec "${container_id}" sh -lc "tar -czf - -C /var/lib prometheus" \
      > "${artifact}/container_paths/var_lib_prometheus_container_path.tar.gz"
  fi
fi

add_readme

if [[ -z "$(ls -A "${artifact}/vm_paths" "${artifact}/container_paths" 2>/dev/null)" ]]; then
  echo "No Prometheus DB directories were found on VM or container." >&2
  exit 1
fi

tar -czf - "${artifact}"
'
EOF
)

FAILURES=0
run_remote_and_collect "prometheusFullDB" "${PROM_KEY}" "${PROM_HOST}" "${PROM_FULL_DB_REMOTE_CMD}" "${PROM_DIR}" || FAILURES=$((FAILURES + 1))

if [[ "${FAILURES}" -gt 0 ]]; then
  log "ERROR" "FINAL" "Completed with ${FAILURES} failed section(s)."
  exit 1
fi

log "INFO" "FINAL" "Completed successfully. Artifacts available at ${OUTPUT_ROOT}"
