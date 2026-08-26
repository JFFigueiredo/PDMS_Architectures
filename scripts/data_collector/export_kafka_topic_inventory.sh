#!/usr/bin/env bash
set -euo pipefail

# Export Kafka topic inventory CSV with:
# topic, partitions, under_replicated_partitions, replication_factor, messages, size_bytes, size_human
#
# Usage:
#   ./export_kafka_topic_inventory.sh [TARGET] [OUTDIR]
#
# TARGET:
#   both (default), local, cloud
#
# Env:
#   LOCAL_BOOTSTRAP=127.0.0.1:9092
#   CLOUD_BOOTSTRAP=<db-host>:9092   # default; override if needed
#   LOCAL_COMMAND_CONFIG=<optional properties path>
#   CLOUD_COMMAND_CONFIG=<optional properties path>
#   LOCAL_KAFKA_CONTAINER=pdms-local-kafka
#   TOPIC_REGEX='^pdms\\.'   # only PDMS topics by default

TARGET="${1:-both}"
if [[ "${TARGET}" != "both" && "${TARGET}" != "local" && "${TARGET}" != "cloud" ]]; then
  TARGET="both"
  OUTDIR="${1:-./kafka_topic_inventory_$(date +%Y%m%d_%H%M%S)}"
else
  OUTDIR="${2:-./kafka_topic_inventory_$(date +%Y%m%d_%H%M%S)}"
fi

LOCAL_BOOTSTRAP="${LOCAL_BOOTSTRAP:-127.0.0.1:9092}"
CLOUD_BOOTSTRAP="${CLOUD_BOOTSTRAP:-<db-host>:9092}"
LOCAL_COMMAND_CONFIG="${LOCAL_COMMAND_CONFIG:-}"
CLOUD_COMMAND_CONFIG="${CLOUD_COMMAND_CONFIG:-}"
LOCAL_KAFKA_CONTAINER="${LOCAL_KAFKA_CONTAINER:-pdms-local-kafka}"
TOPIC_REGEX="${TOPIC_REGEX:-^pdms\\.}"
KAFBAT_API_BASE="${KAFBAT_API_BASE:-http://localhost:9000}"
KAFBAT_TOPIC_PER_PAGE="${KAFBAT_TOPIC_PER_PAGE:-1000}"
KAFBAT_CLUSTER_LOCAL="${KAFBAT_CLUSTER_LOCAL:-local}"
KAFBAT_CLUSTER_CLOUD="${KAFBAT_CLUSTER_CLOUD:-cloud}"

mkdir -p "${OUTDIR}"
LOG_FILE="${OUTDIR}/export.log"

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "${LOG_FILE}"
}

build_cmd_config_args() {
  local cfg="$1"
  if [[ -n "${cfg}" ]]; then
    printf -- "--command-config %q" "${cfg}"
  fi
}

has_local_kafka_cli() {
  command -v kafka-topics.sh >/dev/null 2>&1 &&
    command -v kafka-log-dirs.sh >/dev/null 2>&1 &&
    command -v kafka-run-class.sh >/dev/null 2>&1
}

can_use_docker() {
  docker ps >/dev/null 2>&1
}

run_topics() {
  local cluster="$1"; shift
  if has_local_kafka_cli; then
    kafka-topics.sh "$@"
    return
  fi
  if [[ "${cluster}" == "local" ]]; then
    docker exec "${LOCAL_KAFKA_CONTAINER}" kafka-topics "$@"
    return
  fi
  return 127
}

run_offsets() {
  local cluster="$1"; shift
  if has_local_kafka_cli; then
    kafka-run-class.sh kafka.tools.GetOffsetShell "$@"
    return
  fi
  if [[ "${cluster}" == "local" ]]; then
    docker exec "${LOCAL_KAFKA_CONTAINER}" kafka-run-class kafka.tools.GetOffsetShell "$@"
    return
  fi
  return 127
}

run_log_dirs() {
  local cluster="$1"; shift
  if has_local_kafka_cli; then
    kafka-log-dirs.sh "$@"
    return
  fi
  if [[ "${cluster}" == "local" ]]; then
    docker exec "${LOCAL_KAFKA_CONTAINER}" kafka-log-dirs "$@"
    return
  fi
  return 127
}

cluster_to_kafbat_name() {
  local cluster="$1"
  case "${cluster}" in
    local) printf "%s" "${KAFBAT_CLUSTER_LOCAL}" ;;
    cloud) printf "%s" "${KAFBAT_CLUSTER_CLOUD}" ;;
    *) printf "%s" "${cluster}" ;;
  esac
}

export_cluster_via_kafbat() {
  local cluster="$1"
  local out_csv="$2"
  local kafbat_cluster
  kafbat_cluster="$(cluster_to_kafbat_name "${cluster}")"
  local endpoint="${KAFBAT_API_BASE%/}/api/clusters/${kafbat_cluster}/topics?perPage=${KAFBAT_TOPIC_PER_PAGE}"
  local json_file="${OUTDIR}/.${cluster}_topics_kafbat.json"

  log "Trying Kafbat API fallback for ${cluster}: ${endpoint}"
  if ! curl -fsS "${endpoint}" -o "${json_file}" >>"${LOG_FILE}" 2>&1; then
    log "ERROR: Kafbat API request failed for ${cluster}"
    return 1
  fi

  if ! python3 - "${json_file}" "${out_csv}" "${TOPIC_REGEX}" <<'PY'
import csv
import json
import re
import sys

json_file, out_csv, topic_regex = sys.argv[1:4]

def human_bytes(n: int) -> str:
    units = ["B", "KB", "MB", "GB", "TB"]
    i = 0
    f = float(max(0, n))
    while f >= 1024 and i < len(units) - 1:
        f /= 1024.0
        i += 1
    return f"{f:.0f} {units[i]}"

with open(json_file, "r", encoding="utf-8") as f:
    data = json.load(f)

if not isinstance(data, dict) or "topics" not in data:
    raise SystemExit("Invalid Kafbat topics response")

pattern = re.compile(topic_regex)
topics = data.get("topics", [])

with open(out_csv, "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow([
        "topic",
        "partitions",
        "under_replicated_partitions",
        "replication_factor",
        "messages",
        "size_bytes",
        "size_human",
    ])
    for t in topics:
        name = str(t.get("name", ""))
        if not name or not pattern.search(name):
            continue
        partitions = int(t.get("partitionCount") or 0)
        under_repl = int(t.get("underReplicatedPartitions") or 0)
        replication_factor = int(t.get("replicationFactor") or 0)
        messages = int(t.get("messagesCount") or 0)
        size_bytes = int(t.get("segmentSize") or 0)
        w.writerow([
            name,
            partitions,
            under_repl,
            replication_factor,
            messages,
            size_bytes,
            human_bytes(size_bytes),
        ])
PY
  then
    log "ERROR: failed to convert Kafbat topics response for ${cluster}"
    return 1
  fi

  local topic_count
  topic_count="$(($(wc -l < "${out_csv}") - 1))"
  log "OK: exported ${topic_count} topics via Kafbat API to ${out_csv}"
}

human_bytes() {
  local bytes="$1"
  python3 - <<'PY' "${bytes}"
import sys
n = float(sys.argv[1])
units = ["B", "KB", "MB", "GB", "TB"]
i = 0
while n >= 1024 and i < len(units)-1:
    n /= 1024.0
    i += 1
print(f"{n:.0f} {units[i]}")
PY
}

sum_offsets() {
  # Input lines: topic:partition:offset (stdin must stay the pipe; do not use a heredoc here).
  python3 -c '
import sys
total = 0
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        total += int(line.rsplit(":", 1)[1])
    except Exception:
        pass
print(total)
'
}

extract_topic_size() {
  local topic="$1"
  # Reads kafka-log-dirs JSON on stdin (stdin must be JSON, not a heredoc).
  python3 -c '
import json, sys
topic = sys.argv[1]
raw = sys.stdin.read().strip()
if not raw:
    print(0)
    raise SystemExit(0)
# kafka-log-dirs prints status lines before the JSON object on stdout.
if "{" in raw:
    raw = raw[raw.index("{") :]
try:
    data = json.loads(raw)
except Exception:
    print(0)
    raise SystemExit(0)
total = 0
prefix = topic + "-"
for b in data.get("brokers", []):
    for ld in b.get("logDirs", []):
        for p in ld.get("partitions", []):
            part = p.get("partition", "")
            if part.startswith(prefix):
                total += int(p.get("size", 0))
print(total)
' "${topic}"
}

export_cluster() {
  local cluster="$1"
  local bootstrap="$2"
  local cmd_cfg="$3"
  local out_csv="${OUTDIR}/topic_inventory_${cluster}.csv"
  local topics_file="${OUTDIR}/.${cluster}_topics.txt"

  log "==== Cluster: ${cluster} | bootstrap=${bootstrap} ===="

  local cfg_args=""
  if [[ -n "${cmd_cfg}" ]]; then
    if [[ ! -f "${cmd_cfg}" ]]; then
      log "WARN: command-config not found for ${cluster}: ${cmd_cfg}"
    else
      cfg_args="$(build_cmd_config_args "${cmd_cfg}")"
      log "Using command-config: ${cmd_cfg}"
    fi
  fi

  # shellcheck disable=SC2086
  if ! eval "run_topics \"${cluster}\" --bootstrap-server \"${bootstrap}\" ${cfg_args} --list" > "${topics_file}" 2>>"${LOG_FILE}"; then
    log "WARN: cannot list topics for ${cluster} via Kafka CLI; trying Kafbat API fallback"
    if export_cluster_via_kafbat "${cluster}" "${out_csv}"; then
      return 0
    fi
    log "ERROR: cannot list topics for ${cluster} via CLI or Kafbat API"
    return 1
  fi

  # Keep only requested topic namespace
  rg "${TOPIC_REGEX}" "${topics_file}" > "${topics_file}.filtered" || true
  mv "${topics_file}.filtered" "${topics_file}"

  {
    echo "topic,partitions,under_replicated_partitions,replication_factor,messages,size_bytes,size_human"
    while IFS= read -r topic; do
      [[ -z "${topic}" ]] && continue

      # shellcheck disable=SC2086
      local desc
      desc="$(eval "run_topics \"${cluster}\" --bootstrap-server \"${bootstrap}\" ${cfg_args} --describe --topic \"${topic}\"" 2>>"${LOG_FILE}" || true)"
      if [[ -z "${desc}" ]]; then
        echo "\"${topic}\",0,0,0,0,0,\"0 B\""
        continue
      fi

      local partitions rf under_repl
      partitions="$(echo "${desc}" | awk -F'PartitionCount:|ReplicationFactor:' 'NR==1{gsub(/ /,"",$2); print $2}')"
      rf="$(echo "${desc}" | awk -F'ReplicationFactor:' 'NR==1{gsub(/ /,"",$2); split($2,a," "); print a[1]}')"
      partitions="$(echo "${partitions}" | tr -d '[:space:]')"
      rf="$(echo "${rf}" | tr -d '[:space:]')"
      under_repl="$(printf '%s\n' "${desc}" | python3 -c '
import re, sys
rf = int(sys.argv[1] or 0)
count = 0
for line in sys.stdin:
    if "ISR:" not in line:
        continue
    m = re.search(r"ISR:\s*([0-9,]+)", line)
    if not m:
        continue
    isr = [x for x in m.group(1).split(",") if x.strip()]
    if rf > 0 and len(isr) < rf:
        count += 1
print(count)
' "${rf:-0}")"

      # shellcheck disable=SC2086
      local latest earliest messages latest_raw earliest_raw
      latest_raw="$(eval "run_offsets \"${cluster}\" --bootstrap-server \"${bootstrap}\" ${cfg_args} --topic \"${topic}\" --time -1" 2>>"${LOG_FILE}" | sum_offsets || true)"
      earliest_raw="$(eval "run_offsets \"${cluster}\" --bootstrap-server \"${bootstrap}\" ${cfg_args} --topic \"${topic}\" --time -2" 2>>"${LOG_FILE}" | sum_offsets || true)"
      latest="$(echo "${latest_raw}" | tr -d '[:space:]')"
      earliest="$(echo "${earliest_raw}" | tr -d '[:space:]')"
      [[ "${latest}" =~ ^[0-9]+$ ]] || latest=0
      [[ "${earliest}" =~ ^[0-9]+$ ]] || earliest=0
      messages=$(( latest - earliest ))
      if [[ "${messages}" -lt 0 ]]; then messages=0; fi

      # shellcheck disable=SC2086
      local logdirs_json size_bytes size_human
      logdirs_json="$(eval "run_log_dirs \"${cluster}\" --bootstrap-server \"${bootstrap}\" ${cfg_args} --describe --topic-list \"${topic}\"" 2>>"${LOG_FILE}" || true)"
      size_bytes="$(printf '%s' "${logdirs_json}" | extract_topic_size "${topic}")"
      size_human="$(human_bytes "${size_bytes}")"

      echo "\"${topic}\",\"${partitions:-0}\",\"${under_repl:-0}\",\"${rf:-0}\",\"${messages}\",\"${size_bytes}\",\"${size_human}\""
    done < "${topics_file}"
  } > "${out_csv}"

  local topic_count
  topic_count="$(($(wc -l < "${out_csv}") - 1))"
  log "OK: exported ${topic_count} topics to ${out_csv}"
}

log "Target: ${TARGET}"
log "Output directory: ${OUTDIR}"
log "Topic regex: ${TOPIC_REGEX}"

if ! has_local_kafka_cli; then
  log "Kafka CLI not found locally."
  if [[ "${TARGET}" == "local" || "${TARGET}" == "both" ]]; then
    if ! can_use_docker; then
      log "ERROR: Docker is not accessible for local fallback."
      exit 1
    fi
    log "Using docker fallback for local cluster via container: ${LOCAL_KAFKA_CONTAINER}"
  fi
  if [[ "${TARGET}" == "cloud" || "${TARGET}" == "both" ]]; then
    log "WARN: cloud export may fail without local Kafka CLI binaries."
  fi
fi

FAILED=0
if [[ "${TARGET}" == "both" || "${TARGET}" == "local" ]]; then
  if ! export_cluster "local" "${LOCAL_BOOTSTRAP}" "${LOCAL_COMMAND_CONFIG}"; then
    FAILED=1
  fi
fi
if [[ "${TARGET}" == "both" || "${TARGET}" == "cloud" ]]; then
  if ! export_cluster "cloud" "${CLOUD_BOOTSTRAP}" "${CLOUD_COMMAND_CONFIG}"; then
    FAILED=1
  fi
fi

if [[ "${FAILED}" -ne 0 ]]; then
  log "DONE with errors. Check ${LOG_FILE}"
  exit 1
fi

log "DONE successfully."
if [[ "${TARGET}" == "both" || "${TARGET}" == "local" ]]; then
  log "  - ${OUTDIR}/topic_inventory_local.csv"
fi
if [[ "${TARGET}" == "both" || "${TARGET}" == "cloud" ]]; then
  log "  - ${OUTDIR}/topic_inventory_cloud.csv"
fi
