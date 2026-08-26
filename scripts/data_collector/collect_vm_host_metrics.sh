#!/usr/bin/env bash
# Run on each Linux VM (GNU date, sysstat optional). Creates ~/host_metrics_<tag>_<ts>/
#
# Usage: ./collect_vm_host_metrics.sh <tag>
#   tag: app | db | jmeter | prom
#
# Historical CPU/RAM for the last ~60 minutes:
#   Requires package sysstat and periodic sa1 writes to /var/log/sysstat/saDD.
#   If missing, see sar_unavailable.txt (the bundle will still include live snapshots).
set -euo pipefail

TAG="${1:?usage: $0 app|db|jmeter|prom|besu}"
case "${TAG}" in
  app|db|jmeter|prom|besu) ;;
  *) echo "Invalid tag: ${TAG}" >&2; exit 2 ;;
esac

TS="$(date +%Y%m%d_%H%M%S)"
OUT="${HOME}/host_metrics_${TAG}_${TS}"
mkdir -p "${OUT}"

append_readme() {
  printf '%s\n' "$*" >> "${OUT}/README.txt"
}

{
  echo "=== collection (local) ==="
  date
  echo "=== collection UTC ==="
  date -u
  echo "=== hostname ==="
  hostname -f 2>/dev/null || hostname
  echo "=== uptime ==="
  uptime
  echo "=== free -h ==="
  free -h
  echo "=== loadavg ==="
  cat /proc/loadavg
  echo "=== df -h (first 40 lines) ==="
  df -h 2>/dev/null | head -n 40
  echo "=== nproc ==="
  nproc
  echo "=== vmstat 1 10 (10s live sample at collection time) ==="
  vmstat 1 10
} > "${OUT}/snapshot_at_collection.txt" 2>&1 || true

append_readme "Per-VM host metrics bundle (tag=${TAG})."
append_readme ""
append_readme "snapshot_at_collection.txt — point-in-time and short vmstat sample when this script ran."
append_readme ""
append_readme "Last-hour CPU/RAM (approx):"
append_readme "  - If sar_cpu_last_hour.txt / sar_mem_last_hour.txt exist and are non-empty, data came from"
append_readme "    sysstat archives under /var/log/sysstat/saDD (local time, day-of-month DD)."
append_readme "  - Resolution depends on /etc/cron.d/sysstat or systemd sysstat-collect.timer (often 10 min)."
append_readme "  - Windows crossing local midnight are split across two saDD files when possible."
append_readme ""
append_readme "Without sysstat: install sysstat and ensure the collector service/timer is enabled."
append_readme "If unavailable, use snapshot_at_collection.txt and docker_stats_no_stream.txt as point-in-time fallback."

collect_sar_last_hour() {
  local sar_bin="$1"
  local cpu_out="${OUT}/sar_cpu_last_hour.txt"
  local mem_out="${OUT}/sar_mem_last_hour.txt"

  if [[ ! -d /var/log/sysstat ]]; then
    echo "/var/log/sysstat not found (sysstat not collecting?)" > "${OUT}/sar_unavailable.txt"
    return 0
  fi

  local d_start t_start d_end t_end file file1 file2
  d_start="$(date -d '1 hour ago' +%d)"
  t_start="$(date -d '1 hour ago' +%H:%M:%S)"
  d_end="$(date +%d)"
  t_end="$(date +%H:%M:%S)"

  if [[ "${d_start}" == "${d_end}" ]]; then
    file="/var/log/sysstat/sa${d_end}"
    if [[ ! -f "${file}" ]]; then
      echo "Missing ${file}" > "${OUT}/sar_unavailable.txt"
      return 0
    fi
    "${sar_bin}" -u -f "${file}" -s "${t_start}" -e "${t_end}" > "${cpu_out}" 2>/dev/null || echo "(sar -u failed or no samples)" >> "${cpu_out}"
    "${sar_bin}" -r -f "${file}" -s "${t_start}" -e "${t_end}" > "${mem_out}" 2>/dev/null || echo "(sar -r failed or no samples)" >> "${mem_out}"
  else
    file1="/var/log/sysstat/sa${d_start}"
    file2="/var/log/sysstat/sa${d_end}"
    {
      echo "=== CPU day ${d_start} ${t_start} - 23:59:59 ==="
      if [[ -f "${file1}" ]]; then
        "${sar_bin}" -u -f "${file1}" -s "${t_start}" -e "23:59:59" 2>/dev/null || true
      else
        echo "missing ${file1}"
      fi
      echo "=== CPU day ${d_end} 00:00:00 - ${t_end} ==="
      if [[ -f "${file2}" ]]; then
        "${sar_bin}" -u -f "${file2}" -s "00:00:00" -e "${t_end}" 2>/dev/null || true
      else
        echo "missing ${file2}"
      fi
    } > "${cpu_out}"
    {
      echo "=== RAM day ${d_start} ${t_start} - 23:59:59 ==="
      if [[ -f "${file1}" ]]; then
        "${sar_bin}" -r -f "${file1}" -s "${t_start}" -e "23:59:59" 2>/dev/null || true
      else
        echo "missing ${file1}"
      fi
      echo "=== RAM day ${d_end} 00:00:00 - ${t_end} ==="
      if [[ -f "${file2}" ]]; then
        "${sar_bin}" -r -f "${file2}" -s "00:00:00" -e "${t_end}" 2>/dev/null || true
      else
        echo "missing ${file2}"
      fi
    } > "${mem_out}"
  fi

  if [[ ! -s "${cpu_out}" ]] && [[ ! -s "${mem_out}" ]]; then
    echo "sar produced no rows for the requested window (sysstat interval too coarse or no sa file)." > "${OUT}/sar_unavailable.txt"
  fi
}

if command -v sar >/dev/null 2>&1; then
  collect_sar_last_hour sar
else
  {
    echo "sar not in PATH."
    echo "On Debian/Ubuntu: sudo apt-get install -y sysstat && sudo systemctl enable --now sysstat"
  } > "${OUT}/sar_unavailable.txt"
fi

# Optional: cgroup / docker snapshot (current only)
if command -v docker >/dev/null 2>&1; then
  docker ps -a --format 'table {{.Names}}\t{{.Status}}' > "${OUT}/docker_ps.txt" 2>&1 || true
  docker stats --no-stream > "${OUT}/docker_stats_no_stream.txt" 2>&1 || true
  # Applied (not declared) resource limits: NanoCpus / CpuQuota / Memory per container.
  # docker stats shows a memory-limit column but no CPU quota, so this is the only
  # artifact that can evidence CPU-limit enforcement on the host.
  docker inspect $(docker ps -q) > "${OUT}/docker_inspect.json" 2>/dev/null || true
fi

echo "${OUT}"
