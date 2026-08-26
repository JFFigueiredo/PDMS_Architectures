#!/usr/bin/env bash
set -euo pipefail

# Collect Besu runtime artifacts for offline analysis (logs, inspect, JSON-RPC snapshots).
# Runs on the Besu VM. From 2026-08 Besu has its own machine, so this bundle is the
# primary evidence for what the ledger costs (CPU/memory series, chain size, block count).
#
# Usage:
#   ./collect_besu_data.sh <variant>
# Example:
#   ./collect_besu_data.sh decentralized
#
# Environment (optional):
#   BESU_RPC_URL              default http://127.0.0.1:8545
#   BESU_CONTAINER_NAME     force container name (otherwise first running name matching *besu*)
#   BESU_DOCKER_LOG_LINES     default 200000 (docker logs --tail)

VARIANT="${1:-decentralized}"
OUTDIR="./besu_data_${VARIANT}_$(date +%Y%m%d_%H%M%S)"
mkdir -p "${OUTDIR}"

rpc_url="${BESU_RPC_URL:-http://127.0.0.1:8545}"
log_lines="${BESU_DOCKER_LOG_LINES:-200000}"

log() { echo "[Besu] $*" | tee -a "${OUTDIR}/besu_collection.log"; }

find_besu_container() {
  if [[ -n "${BESU_CONTAINER_NAME:-}" ]]; then
    echo "${BESU_CONTAINER_NAME}"
    return
  fi
  docker ps --format '{{.Names}}' 2>/dev/null | grep -i besu | head -n1 || true
}

jsonrpc() {
  local method="$1"
  local params="${2:-[]}"
  curl -sf -m 20 -X POST -H 'Content-Type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"method\":\"${method}\",\"params\":${params},\"id\":1}" \
    "${rpc_url}" 2>/dev/null || printf '%s\n' "{\"error\":\"curl_failed\",\"method\":\"${method}\"}"
}

log "Collecting into ${OUTDIR} (variant=${VARIANT}, rpc=${rpc_url})"

{
  echo "date_utc=$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "host=$(hostname -f 2>/dev/null || hostname)"
  echo "variant=${VARIANT}"
  echo "besu_rpc_url=${rpc_url}"
  uname -a || true
  docker version 2>/dev/null || true
} > "${OUTDIR}/metadata.txt"

cid="$(find_besu_container)"
echo "besu_container=${cid:-}" >> "${OUTDIR}/metadata.txt"
printf '%s\n' "${cid}" > "${OUTDIR}/besu_container_name.txt"

if [[ -n "${cid}" ]]; then
  docker inspect "${cid}" > "${OUTDIR}/docker_inspect.json" 2>&1
  docker logs --tail "${log_lines}" "${cid}" > "${OUTDIR}/docker_logs.txt" 2>&1 || true
  docker stats "${cid}" --no-stream > "${OUTDIR}/docker_stats_once.txt" 2>&1 || true
  docker exec "${cid}" sh -c "ls -la /opt/besu/data 2>/dev/null || ls -la /data 2>/dev/null || true" > "${OUTDIR}/data_dir_listing.txt" 2>&1 || true
else
  log "WARN: no running container with name matching 'besu'. Set BESU_CONTAINER_NAME or start Besu."
  echo "No Besu container found; set BESU_CONTAINER_NAME or ensure a container name contains 'besu'." > "${OUTDIR}/ERROR_no_besu_container.txt"
fi

# --- ledger cost signals -----------------------------------------------------
# These exist to price the ledger: how much CPU/memory it drew, how much disk the
# chain consumed, and how many blocks/anchors were produced during the run.
if [[ -n "${cid}" ]]; then
  # Short docker stats series instead of a single sample. docker_stats_once.txt above
  # is one instant; this gives a crude time series for mean/peak.
  samples="${BESU_STATS_SAMPLES:-10}"; interval="${BESU_STATS_INTERVAL:-2}"
  log "Sampling docker stats ${samples}x every ${interval}s"
  {
    echo "ts_utc,cpu_pct,mem_usage,mem_limit,mem_pct,net_io,block_io,pids"
    for _ in $(seq 1 "${samples}"); do
      line="$(docker stats "${cid}" --no-stream --format '{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}},{{.PIDs}}' 2>/dev/null || echo 'NA')"
      echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),${line}"
      sleep "${interval}"
    done
  } > "${OUTDIR}/docker_stats_series.csv" 2>&1 || true

  # Chain size on disk = the storage cost of anchoring.
  docker exec "${cid}" sh -c 'du -sb /opt/besu/data 2>/dev/null || du -sb /data 2>/dev/null || true' \
    > "${OUTDIR}/chain_data_size_bytes.txt" 2>&1 || true
  docker exec "${cid}" sh -c 'du -sh /opt/besu/data 2>/dev/null || du -sh /data 2>/dev/null || true' \
    > "${OUTDIR}/chain_data_size_human.txt" 2>&1 || true
fi

# Besu's own Prometheus endpoint, when enabled (--metrics-enabled, default port 9545).
# Gives block-processing and JVM time series that host metrics cannot show.
besu_metrics_url="${BESU_METRICS_URL:-http://127.0.0.1:9545/metrics}"
if curl -sf -m 10 "${besu_metrics_url}" > "${OUTDIR}/besu_prometheus_metrics.txt" 2>/dev/null; then
  log "Besu metrics endpoint captured (${besu_metrics_url})"
else
  rm -f "${OUTDIR}/besu_prometheus_metrics.txt"
  echo "Not reachable at ${besu_metrics_url}; start Besu with --metrics-enabled to capture it." \
    > "${OUTDIR}/besu_metrics_unavailable.txt"
fi

# Latest block: gasUsed and tx count price one anchoring transaction.
jsonrpc eth_getBlockByNumber '["latest",false]' > "${OUTDIR}/rpc_latest_block.json" || true
jsonrpc net_peerCount > "${OUTDIR}/rpc_net_peerCount.json" || true
jsonrpc eth_gasPrice > "${OUTDIR}/rpc_eth_gasPrice.json" || true
# -----------------------------------------------------------------------------

jsonrpc eth_blockNumber > "${OUTDIR}/rpc_eth_blockNumber.json" || true
jsonrpc eth_chainId > "${OUTDIR}/rpc_eth_chainId.json" || true
jsonrpc net_version > "${OUTDIR}/rpc_net_version.json" || true
jsonrpc eth_syncing > "${OUTDIR}/rpc_eth_syncing.json" || true
jsonrpc txpool_besuStatus > "${OUTDIR}/rpc_txpool_besuStatus.json" || true

log "DONE - ${OUTDIR}"
