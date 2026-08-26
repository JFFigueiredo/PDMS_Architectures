#!/usr/bin/env bash
set -euo pipefail

# Collect all Prometheus query_range data required by grafana/dashboards/pdms-dashboard.json
#
# Usage:
#   ./collect_prometheus_metrics.sh [START_TS] [END_TS] [STEP] [PROM_URL] [OUTDIR]
#
# Example:
#   ./collect_prometheus_metrics.sh 1777302000 1777305600 15s http://localhost:9090
#
# No timestamps provided:
#   Collects the previous full UTC hour automatically.
#
# Optional TSDB snapshot export (default: enabled):
#   ENABLE_TSDB_SNAPSHOT=1 (default)
#   PROMETHEUS_DATA_DIR=/var/lib/prometheus
#   SNAPSHOT_SKIP_HEAD=false
#   ENABLE_TSDB_DIR_BACKUP=1 (default; fallback tar.gz backup when snapshot API is unavailable)

START_TS="${1:-}"
END_TS="${2:-}"
STEP="${3:-15s}"
PROM_URL="${4:-http://localhost:9090}"
OUTDIR="${5:-}"
ENABLE_TSDB_SNAPSHOT="${ENABLE_TSDB_SNAPSHOT:-1}"
PROMETHEUS_DATA_DIR="${PROMETHEUS_DATA_DIR:-/var/lib/prometheus}"
SNAPSHOT_SKIP_HEAD="${SNAPSHOT_SKIP_HEAD:-false}"
ENABLE_TSDB_DIR_BACKUP="${ENABLE_TSDB_DIR_BACKUP:-1}"
TSDB_MIN_BYTES="${TSDB_MIN_BYTES:-131072}"

resolve_prometheus_data_dir() {
  local configured="$1"
  local -a candidates=(
    "${configured}"
    "/prometheus"
    "/var/lib/prometheus"
    "/opt/prometheus/data"
    "${HOME}/prometheus"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -d "${candidate}" ]]; then
      echo "${candidate}"
      return 0
    fi
  done
  echo "${configured}"
}

resolve_container_mount_source_for_path() {
  local container_bin="$1"
  local container_id source
  for container_id in $(${container_bin} ps -q 2>/dev/null); do
    source="$(${container_bin} inspect "${container_id}" --format "{{range .Mounts}}{{if eq .Destination \"${PROMETHEUS_DATA_DIR}\"}}{{.Source}}{{end}}{{end}}" 2>/dev/null || true)"
    if [[ -n "${source}" && -d "${source}" ]]; then
      echo "${source}"
      return 0
    fi
  done
  return 1
}

detect_prometheus_data_dir_from_flags() {
  local flags_url="${PROM_URL%/}/api/v1/status/flags"
  local resp detected
  if ! resp="$(curl -fsS "${flags_url}" 2>/dev/null)"; then
    return 1
  fi
  detected="$(python3 - <<'PY' "${resp}"
import json, sys
try:
    doc = json.loads(sys.argv[1])
except Exception:
    print("")
    raise SystemExit(0)
data = doc.get("data", {})
print(data.get("storage.tsdb.path", "") or data.get("storage_tsdb_path", ""))
PY
)"
  if [[ -n "${detected}" ]]; then
    echo "${detected}"
    return 0
  fi
  return 1
}

if PROM_FROM_FLAGS="$(detect_prometheus_data_dir_from_flags)"; then
  PROMETHEUS_DATA_DIR="${PROM_FROM_FLAGS}"
else
  PROMETHEUS_DATA_DIR="$(resolve_prometheus_data_dir "${PROMETHEUS_DATA_DIR}")"
fi
if [[ ! -d "${PROMETHEUS_DATA_DIR}" ]]; then
  if command -v docker >/dev/null 2>&1; then
    if PROM_FROM_DOCKER="$(resolve_container_mount_source_for_path docker)"; then
      PROMETHEUS_DATA_DIR="${PROM_FROM_DOCKER}"
    fi
  fi
fi
if [[ ! -d "${PROMETHEUS_DATA_DIR}" ]]; then
  if command -v podman >/dev/null 2>&1; then
    if PROM_FROM_PODMAN="$(resolve_container_mount_source_for_path podman)"; then
      PROMETHEUS_DATA_DIR="${PROM_FROM_PODMAN}"
    fi
  fi
fi
echo "Using PROMETHEUS_DATA_DIR=${PROMETHEUS_DATA_DIR}"

if [[ -z "${START_TS}" && -z "${END_TS}" ]]; then
  NOW_UTC="$(date -u +%s)"
  CURRENT_HOUR_START="$((NOW_UTC - (NOW_UTC % 3600)))"
  START_TS="$((CURRENT_HOUR_START - 3600))"
  END_TS="${CURRENT_HOUR_START}"
  echo "No timestamps provided. Using previous full UTC hour:"
  echo "  START_TS=${START_TS} ($(date -u -r "${START_TS}" +"%Y-%m-%dT%H:%M:%SZ"))"
  echo "  END_TS=${END_TS} ($(date -u -r "${END_TS}" +"%Y-%m-%dT%H:%M:%SZ"))"
elif [[ -z "${START_TS}" || -z "${END_TS}" ]]; then
  echo "Usage: $0 [START_TS] [END_TS] [STEP] [PROM_URL] [OUTDIR]"
  echo "Hint: provide both START_TS and END_TS, or provide neither to auto-use previous full UTC hour."
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DASHBOARD_JSON="${DASHBOARD_JSON:-${REPO_ROOT}/grafana/dashboards/pdms-dashboard.json}"

if [[ -z "${OUTDIR}" ]]; then
  OUTDIR="./prometheus_metrics_$(date +%Y%m%d_%H%M%S)"
fi
mkdir -p "${OUTDIR}/queries"

TMP_QUERY_LIST="${OUTDIR}/_query_list.json"

python3 - <<'PY' "${DASHBOARD_JSON}" "${TMP_QUERY_LIST}"
import json, re, sys
from pathlib import Path

dashboard_path = Path(sys.argv[1])
out_path = Path(sys.argv[2])
if dashboard_path.exists():
    doc = json.loads(dashboard_path.read_text(encoding="utf-8"))
else:
    doc = {"panels": []}

def slug(s: str) -> str:
    s = s.lower()
    s = re.sub(r"[^a-z0-9]+", "_", s)
    return s.strip("_")

items = []
seen = {}

def walk(panels):
    for panel in panels:
        title = panel.get("title", "untitled")
        for t in panel.get("targets", []):
            expr = (t.get("expr") or "").strip()
            if not expr:
                continue
            ref = t.get("refId", "A")
            key = (title, ref, expr)
            if key in seen:
                continue
            seen[key] = True
            fname = f"{slug(title)}_{slug(ref)}.json"
            # keep unique filename if duplicate title/ref appears
            idx = 1
            base = fname[:-5]
            while any(x["output_file"] == fname for x in items):
                idx += 1
                fname = f"{base}_{idx}.json"
            items.append({
                "panel_title": title,
                "ref_id": ref,
                "query": expr,
                "output_file": fname
            })
        if isinstance(panel.get("panels"), list):
            walk(panel["panels"])

walk(doc.get("panels", []))

# Always include core latency aggregates used across historical dashboards.
required_queries = [
    {
        "panel_title": "Response time total",
        "ref_id": "A_sum_rate",
        "query": "sum(rate(http_server_requests_seconds_sum{uri!~\"/actuator.*\"}[1m]))",
        "output_file": "http_latency_sum_rate_no_actuator.json",
    },
    {
        "panel_title": "Response time total",
        "ref_id": "A_count_rate",
        "query": "sum(rate(http_server_requests_seconds_count{uri!~\"/actuator.*\"}[1m]))",
        "output_file": "http_latency_count_rate_no_actuator.json",
    },
    {
        "panel_title": "Response time total",
        "ref_id": "A",
        "query": "sum(rate(http_server_requests_seconds_sum{uri!~\"/actuator.*\"}[1m])) / sum(rate(http_server_requests_seconds_count{uri!~\"/actuator.*\"}[1m]))",
        "output_file": "http_mean_latency.json",
    },
    {
        "panel_title": "Response time total",
        "ref_id": "B",
        "query": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri!~\"/actuator.*\"}[1m])) by (le))",
        "output_file": "http_p95_latency.json",
    },
    {
        "panel_title": "Response time total",
        "ref_id": "C",
        "query": "histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{uri!~\"/actuator.*\"}[1m])) by (le))",
        "output_file": "http_p99_latency.json",
    },
    {
        "panel_title": "CPU Usage",
        "ref_id": "A",
        "query": "system_cpu_usage * 100",
        "output_file": "cpu_usage_pct.json",
    },
]

for required in required_queries:
    if any(x["output_file"] == required["output_file"] for x in items):
        continue
    items.append(required)

out_path.write_text(json.dumps(items, indent=2), encoding="utf-8")
print(len(items))
PY

QUERY_COUNT="$(python3 - <<'PY' "${TMP_QUERY_LIST}"
import json, sys
items=json.loads(open(sys.argv[1], encoding='utf-8').read())
print(len(items))
PY
)"

echo "Collecting ${QUERY_COUNT} dashboard queries into: ${OUTDIR}"

python3 - <<'PY' "${TMP_QUERY_LIST}" "${PROM_URL}" "${START_TS}" "${END_TS}" "${STEP}" "${OUTDIR}"
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

query_list_path = Path(sys.argv[1])
prom_url = sys.argv[2].rstrip("/")
start_ts = sys.argv[3]
end_ts = sys.argv[4]
step = sys.argv[5]
outdir = Path(sys.argv[6])

items = json.loads(query_list_path.read_text(encoding="utf-8"))
manifest = {
    "version": 1,
    "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "source": {
        "collector_script": "collect_prometheus_metrics.sh",
        "dashboard": "grafana/dashboards/pdms-dashboard.json",
        "prometheus_url": prom_url,
        "range": {
            "start_ts": int(start_ts),
            "end_ts": int(end_ts),
            "step": step
        }
    },
    "items": []
}

quality = []

for i, item in enumerate(items, start=1):
    query = item["query"]
    out_file = outdir / item["output_file"]
    params = urllib.parse.urlencode(
        {"query": query, "start": start_ts, "end": end_ts, "step": step}
    )
    url = f"{prom_url}/api/v1/query_range?{params}"

    status = "error"
    err_msg = ""
    payload = {"status": "error", "data": {"result": []}}
    try:
        with urllib.request.urlopen(url, timeout=90) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            payload = json.loads(raw)
            status = payload.get("status", "unknown")
    except Exception as exc:
        err_msg = str(exc)

    out_file.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    result = payload.get("data", {}).get("result", [])
    series_count = len(result)
    points_total = 0
    points_finite = 0
    points_nan = 0
    ts_min = None
    ts_max = None

    for series in result:
        for pair in series.get("values", []):
            if len(pair) != 2:
                continue
            ts, raw_val = pair
            points_total += 1
            try:
                val = float(raw_val)
                if math.isfinite(val):
                    points_finite += 1
                else:
                    points_nan += 1
            except Exception:
                points_nan += 1
            try:
                ts_i = int(float(ts))
                ts_min = ts_i if ts_min is None or ts_i < ts_min else ts_min
                ts_max = ts_i if ts_max is None or ts_i > ts_max else ts_max
            except Exception:
                pass

    manifest["items"].append({
        "panel_title": item["panel_title"],
        "ref_id": item["ref_id"],
        "query": query,
        "output_file": item["output_file"],
        "status": status,
        "series_count": series_count,
        "points_total": points_total,
        "points_finite": points_finite,
        "points_nan": points_nan,
        "time_min": ts_min,
        "time_max": ts_max,
        "error": err_msg
    })

    quality.append({
        "output_file": item["output_file"],
        "panel_title": item["panel_title"],
        "series_count": series_count,
        "points_total": points_total,
        "points_finite": points_finite,
        "points_nan": points_nan,
        "time_min": ts_min,
        "time_max": ts_max
    })

    print(f"[{i}/{len(items)}] {item['output_file']} -> series={series_count}, finite={points_finite}, nan={points_nan}")

(outdir / "queries_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
(outdir / "quality_report.json").write_text(json.dumps(quality, indent=2), encoding="utf-8")

ok = sum(1 for x in manifest["items"] if x["status"] == "success")
print(f"Done. Successful queries: {ok}/{len(items)}")
print(f"Manifest: {outdir / 'queries_manifest.json'}")
print(f"Quality:  {outdir / 'quality_report.json'}")
PY

cp "${TMP_QUERY_LIST}" "${OUTDIR}/query_definitions.json"
rm -f "${TMP_QUERY_LIST}"

export_tsdb_snapshot() {
  local api_url="${PROM_URL%/}/api/v1/admin/tsdb/snapshot?skip_head=${SNAPSHOT_SKIP_HEAD}"
  local snapshot_resp snapshot_name snapshot_dir target_dir backup_tar backup_size snapshot_meta_count snapshot_blocks
  create_container_tsdb_backup() {
    local backup_tar_path="$1"
    local runtime="$2"
    local container_id data_parent data_leaf
    container_id="$(${runtime} ps --filter "ancestor=prom/prometheus" -q | head -n1)"
    if [[ -z "${container_id}" ]]; then
      container_id="$(${runtime} ps --filter "ancestor=prom/prometheus:v3.11.3" -q | head -n1)"
    fi
    if [[ -z "${container_id}" ]]; then
      return 1
    fi
    data_parent="$(dirname "${PROMETHEUS_DATA_DIR}")"
    data_leaf="$(basename "${PROMETHEUS_DATA_DIR}")"
    if ! ${runtime} exec "${container_id}" sh -lc "test -d '${PROMETHEUS_DATA_DIR}'" >/dev/null 2>&1; then
      return 1
    fi
    if ${runtime} exec "${container_id}" sh -lc "tar -czf - -C '${data_parent}' '${data_leaf}'" > "${backup_tar_path}"; then
      return 0
    fi
    return 1
  }

  create_fallback_backup() {
    mkdir -p "${OUTDIR}/tsdb_snapshot"
    backup_tar="${OUTDIR}/tsdb_snapshot/tsdb_data_backup.tar.gz"
    echo "==> Creating fallback TSDB directory backup from ${PROMETHEUS_DATA_DIR}"
    if tar -czf "${backup_tar}" -C "$(dirname "${PROMETHEUS_DATA_DIR}")" "$(basename "${PROMETHEUS_DATA_DIR}")"; then
      backup_size="$(wc -c < "${backup_tar}" | tr -d ' ')"
      if [[ "${backup_size}" -lt "${TSDB_MIN_BYTES}" ]]; then
        echo "ERROR: TSDB fallback backup is too small (${backup_size} bytes)."
        echo "       Expected at least ${TSDB_MIN_BYTES} bytes."
        echo "       Refusing to continue with an invalid TSDB backup."
        return 1
      fi
      cat > "${OUTDIR}/tsdb_snapshot/README.txt" <<EOF
Prometheus TSDB fallback backup (filesystem tar.gz) created from:
  ${PROMETHEUS_DATA_DIR}

Reason:
  Snapshot API unavailable or snapshot path inaccessible from host filesystem

Important:
  This backup may be less crash-consistent than an admin snapshot.
EOF
      echo "==> TSDB fallback backup saved to: ${backup_tar} (${backup_size} bytes)"
      return 0
    fi
    echo "WARN: Host filesystem backup failed for ${PROMETHEUS_DATA_DIR}; trying container backup."
    if command -v docker >/dev/null 2>&1 && create_container_tsdb_backup "${backup_tar}" docker; then
      backup_size="$(wc -c < "${backup_tar}" | tr -d ' ')"
      if [[ "${backup_size}" -lt "${TSDB_MIN_BYTES}" ]]; then
        echo "ERROR: TSDB container backup is too small (${backup_size} bytes)."
        echo "       Expected at least ${TSDB_MIN_BYTES} bytes."
        return 1
      fi
      echo "==> TSDB container backup saved to: ${backup_tar} (${backup_size} bytes)"
      return 0
    elif command -v podman >/dev/null 2>&1 && create_container_tsdb_backup "${backup_tar}" podman; then
      backup_size="$(wc -c < "${backup_tar}" | tr -d ' ')"
      if [[ "${backup_size}" -lt "${TSDB_MIN_BYTES}" ]]; then
        echo "ERROR: TSDB container backup is too small (${backup_size} bytes)."
        echo "       Expected at least ${TSDB_MIN_BYTES} bytes."
        return 1
      fi
      echo "==> TSDB container backup saved to: ${backup_tar} (${backup_size} bytes)"
      return 0
    fi
    echo "ERROR: Failed to create fallback TSDB backup from host path or running container."
    return 1
  }


  echo "==> Requesting Prometheus TSDB snapshot from ${api_url}"

  if ! snapshot_resp="$(curl -fsS -XPOST "${api_url}" 2>/dev/null)"; then
    echo "WARN: Could not create TSDB snapshot via admin API."
    echo "      Ensure Prometheus is started with --web.enable-admin-api."
    if [[ "${ENABLE_TSDB_DIR_BACKUP}" == "1" ]]; then
      create_fallback_backup || return 1
    fi
    return 0
  fi

  snapshot_name="$(python3 - <<'PY' "${snapshot_resp}"
import json, sys
try:
    doc = json.loads(sys.argv[1])
except Exception:
    print("")
    raise SystemExit(0)
print(doc.get("data", {}).get("name", ""))
PY
)"

  if [[ -z "${snapshot_name}" ]]; then
    echo "WARN: Snapshot API response missing snapshot name."
    return 0
  fi

  snapshot_dir="${PROMETHEUS_DATA_DIR}/snapshots/${snapshot_name}"
  if [[ ! -d "${snapshot_dir}" ]]; then
    echo "WARN: Snapshot created (${snapshot_name}) but directory not found at:"
    echo "      ${snapshot_dir}"
    echo "      Trying fallback TSDB backup instead."
    if [[ "${ENABLE_TSDB_DIR_BACKUP}" == "1" ]]; then
      create_fallback_backup || return 1
      return 0
    fi
    echo "      Set PROMETHEUS_DATA_DIR to the TSDB path and re-run."
    return 0
  fi

  target_dir="${OUTDIR}/tsdb_snapshot/${snapshot_name}"
  mkdir -p "${OUTDIR}/tsdb_snapshot"
  cp -R "${snapshot_dir}" "${target_dir}"
  snapshot_meta_count="$(rg --files "${target_dir}" -g '**/meta.json' | wc -l | tr -d ' ')"
  snapshot_blocks="$(rg --files "${target_dir}" -g '**/chunks/**' | wc -l | tr -d ' ')"
  if [[ "${snapshot_meta_count}" -eq 0 || "${snapshot_blocks}" -eq 0 ]]; then
    echo "ERROR: Snapshot copied but appears empty or invalid at ${target_dir}."
    echo "       meta.json files: ${snapshot_meta_count}, chunk files: ${snapshot_blocks}"
    return 1
  fi

  cat > "${OUTDIR}/tsdb_snapshot/README.txt" <<EOF
Prometheus TSDB snapshot copied from:
  ${snapshot_dir}

Snapshot name:
  ${snapshot_name}

Restore note:
  Copy snapshot block dirs into another Prometheus data path while Prometheus is stopped.
EOF

  echo "==> TSDB snapshot copied to: ${target_dir} (meta=${snapshot_meta_count}, chunks=${snapshot_blocks})"
}

if [[ "${ENABLE_TSDB_SNAPSHOT}" == "1" ]]; then
  export_tsdb_snapshot
else
  echo "==> Skipping TSDB snapshot export (ENABLE_TSDB_SNAPSHOT=0)"
fi

echo "All files written to: ${OUTDIR}"
