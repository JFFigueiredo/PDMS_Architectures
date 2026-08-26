#!/usr/bin/env bash
# Collect one-off environment facts that the per-run artifacts do NOT capture:
# OS release, Docker/containerd versions, CPU model, clock sync, sysstat interval,
# PostgreSQL runtime settings, Kafka broker config, Prometheus scrape/retention,
# Besu effective flags, JMeter JVM, and Azure IMDS metadata.
#
# Run once with all five VMs booted. Output: analysis/data/vms/env_facts_<TS>/
#
#   sh collect_env_facts.sh
#
# Safe to re-run; each run writes a fresh timestamped folder.
set -uo pipefail

TS="$(date +%Y%m%d_%H%M%S)"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT="${ROOT}/data/vms/env_facts_${TS}"
mkdir -p "$OUT"

KEYS="${KEYS_DIR:-${HOME}/.ssh/pdms-keys}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=25 -o ServerAliveInterval=15 -o ServerAliveCountMax=8 -o StrictHostKeyChecking=accept-new)

# tag|ssh target|identity (empty = use ~/.ssh/config alias)
# Use the ~/.ssh/config aliases where they exist — they already carry the correct
# IdentityFile (the DB VM key is KafkaAndDB_sec.pem, not DBandKafka.pem).
HOSTS=(
  "app|pdms_sec|"
  "db|dbandkafka_sec|"
  "jmeter|jmeter_sec|"
  "prom|observability_sec|"
  "besu|<ssh-user>@<besu-host>|${KEYS}/besu.pem"
)

log(){ printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/collect.log"; }

# Facts common to every VM — this is what verifies the "all VMs identical" claim.
COMMON_CMD='
echo "=== os-release ==="; cat /etc/os-release 2>/dev/null
echo "=== kernel ==="; uname -a
echo "=== cpu ==="; lscpu 2>/dev/null | grep -iE "model name|^cpu\(s\)|thread|core|socket|mhz|flags: .*(avx|sse4)" | head -12
echo "=== memory ==="; free -h; echo; grep -E "MemTotal|SwapTotal" /proc/meminfo
echo "=== disk ==="; df -h / /var/lib/docker 2>/dev/null | sort -u
echo "=== docker ==="; docker version 2>/dev/null || echo "docker not available to this user"
echo "=== docker info (subset) ==="; docker info 2>/dev/null | grep -iE "server version|storage driver|cgroup|runtime|kernel|operating system|cpus|total memory"
echo "=== containerd ==="; containerd --version 2>/dev/null || ctr --version 2>/dev/null || echo "n/a"
echo "=== clock sync ==="; timedatectl 2>/dev/null; chronyc tracking 2>/dev/null | head -8 || echo "chrony not present"
echo "=== sysstat interval ==="; cat /etc/cron.d/sysstat 2>/dev/null; systemctl cat sysstat-collect.timer 2>/dev/null | grep -iE "OnCalendar|Unit"
echo "=== azure IMDS ==="; curl -s -H Metadata:true --max-time 8 "http://169.254.169.254/metadata/instance/compute?api-version=2021-02-01" 2>/dev/null
echo
echo "=== containers ==="; docker ps -a --format "{{.Names}}\t{{.Image}}\t{{.Status}}" 2>/dev/null
# Image digests at run time. A tag alone lets a reuser pin the same NAME, not the same
# BYTES: tags such as postgres:15 float, and even fixed tags can be re-pushed. Recording
# the RepoDigest here is the only way a later reader can prove byte-identity with the
# image this campaign actually ran. Added 2026-08-18; runs before this date have tags only.
echo "=== image digests (running containers) ==="
for _c in $(docker ps --format "{{.Names}}" 2>/dev/null); do
  _img=$(docker inspect "$_c" --format "{{.Config.Image}}" 2>/dev/null)
  _dig=$(docker image inspect "$_img" --format "{{join .RepoDigests \",\"}}" 2>/dev/null)
  printf "%s\t%s\t%s\n" "$_c" "${_img:-<unknown>}" "${_dig:-<none: locally built or never pulled>}"
done
echo "=== image digests (all local images) ==="
docker images --digests --format "{{.Repository}}:{{.Tag}}\t{{.Digest}}" 2>/dev/null
'

DB_CMD='
echo "=== postgres version ==="; docker exec pdms-postgres psql -U pdms_user -d pdms -tAc "select version()" 2>/dev/null
echo "=== postgres settings (non-default) ==="
docker exec pdms-postgres psql -U pdms_user -d pdms -c "select name, setting, unit, source from pg_settings where source not in ('"'"'default'"'"','"'"'override'"'"') order by name" 2>/dev/null
echo "=== postgres key tuning knobs ==="
docker exec pdms-postgres psql -U pdms_user -d pdms -c "select name, setting, unit from pg_settings where name in ('"'"'max_connections'"'"','"'"'shared_buffers'"'"','"'"'work_mem'"'"','"'"'maintenance_work_mem'"'"','"'"'effective_cache_size'"'"','"'"'wal_buffers'"'"','"'"'checkpoint_timeout'"'"','"'"'max_wal_size'"'"','"'"'synchronous_commit'"'"','"'"'fsync'"'"','"'"'random_page_cost'"'"','"'"'max_worker_processes'"'"','"'"'autovacuum'"'"')" 2>/dev/null
echo "=== database size / table counts ==="
docker exec pdms-postgres psql -U pdms_user -d pdms -c "select pg_size_pretty(pg_database_size('"'"'pdms'"'"')) as db_size" 2>/dev/null
docker exec pdms-postgres psql -U pdms_user -d pdms -c "\dt" 2>/dev/null
echo "=== kafka broker config ==="
docker exec pdms-kafka kafka-configs --bootstrap-server localhost:9092 --entity-type brokers --entity-name 1 --describe --all 2>/dev/null | head -40 || echo "kafka-configs unavailable"
echo "=== kafka topics ==="
docker exec pdms-kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null
docker exec pdms-kafka kafka-topics --bootstrap-server localhost:9092 --describe 2>/dev/null | head -30
echo "=== kafka CLI presence (known gap: message dumps failed) ==="
docker exec pdms-kafka bash -lc "ls /usr/bin | grep -i kafka | head -20; command -v kafka-console-consumer.sh || echo NO_kafka-console-consumer.sh; command -v kafka-console-consumer || echo NO_kafka-console-consumer" 2>/dev/null
'

PROM_CMD='
echo "=== prometheus config ==="; docker exec pdms-prometheus cat /etc/prometheus/prometheus.yml 2>/dev/null
echo "=== prometheus flags (scrape/retention) ==="; curl -s --max-time 8 http://localhost:9090/api/v1/status/flags 2>/dev/null
echo "=== prometheus runtime info ==="; curl -s --max-time 8 http://localhost:9090/api/v1/status/runtimeinfo 2>/dev/null
echo "=== prometheus build ==="; curl -s --max-time 8 http://localhost:9090/api/v1/status/buildinfo 2>/dev/null
echo "=== targets ==="; curl -s --max-time 8 "http://localhost:9090/api/v1/targets?state=any" 2>/dev/null | head -c 4000
echo "=== tsdb head stats ==="; curl -s --max-time 8 http://localhost:9090/api/v1/status/tsdb 2>/dev/null | head -c 2000
'

BESU_CMD='
echo "=== besu effective command ==="; docker inspect pdms-decentralized-besu --format "{{json .Config.Cmd}}" 2>/dev/null
echo "=== besu version ==="; docker exec pdms-decentralized-besu besu --version 2>/dev/null || docker logs pdms-decentralized-besu 2>&1 | grep -m1 -i "besu version"
echo "=== chain params ==="
for m in eth_chainId net_version eth_blockNumber eth_gasPrice eth_syncing net_peerCount; do
  printf "%s: " "$m"
  curl -s --max-time 8 -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"$m\",\"params\":[],\"id\":1}" http://localhost:8545 2>/dev/null
  echo
done
echo "=== genesis / network config ==="; docker exec pdms-decentralized-besu sh -lc "ls /opt/besu; cat /opt/besu/genesis.json 2>/dev/null | head -40" 2>/dev/null
echo "=== metrics endpoint sanity ==="; curl -s --max-time 8 http://localhost:9545/metrics 2>/dev/null | grep -c "^besu_"
'

JMETER_CMD='
echo "=== jmeter version ==="; unzip -p /opt/jmeter/lib/ext/ApacheJMeter_core.jar META-INF/MANIFEST.MF 2>/dev/null | grep -i "implementation-version"
echo "=== java for jmeter ==="; java -version 2>&1
echo "=== jmeter heap / JVM args in runner ==="; grep -hE "HEAP|Xmx|Xms|JVM_ARGS" /opt/jmeter/bin/jmeter ~/jmeter/run-*.sh 2>/dev/null | sort -u | head -10
echo "=== runner ladder settings ==="; grep -hE "^THREADS=|^STEPS=|^STEP_DURATION=|^RAMP=" ~/jmeter/run-*.sh 2>/dev/null | sort | uniq -c
echo "=== jmx test plans present ==="; ls -1 ~/jmeter/scripts/*.jmx 2>/dev/null
echo "=== jmeter.properties non-default (subset) ==="; grep -vE "^#|^$" /opt/jmeter/bin/user.properties 2>/dev/null | head -20
'

run(){  # tag target identity extra_cmd
  local tag="$1" target="$2" ident="$3" extra="${4:-}"
  local f="$OUT/${tag}.txt"
  local -a id=()
  [[ -n "$ident" ]] && id=(-i "$ident")
  log "collecting ${tag} (${target})"
  {
    echo "########## HOST: ${tag}  (${target})  collected $(date -u +%FT%TZ) ##########"
    # bash 3.2 (macOS) errors on empty-array expansion under `set -u`; guard it.
    ssh ${id[@]+"${id[@]}"} "${SSH_OPTS[@]}" "$target" "${COMMON_CMD}${extra}" 2>&1
  } > "$f"
  if grep -qiE "Operation timed out|Connection refused|Permission denied|Could not resolve|Connection reset" "$f"; then
    log "  !! ${tag}: UNREACHABLE — is the VM booted?"
    return 1
  fi
  log "  ok ${tag} ($(wc -l < "$f" | tr -d ' ') lines)"
}

log "output -> $OUT"
fail=0
for h in "${HOSTS[@]}"; do
  IFS='|' read -r tag target ident <<< "$h"
  case "$tag" in
    db)     run "$tag" "$target" "$ident" "$DB_CMD"     || fail=$((fail+1)) ;;
    prom)   run "$tag" "$target" "$ident" "$PROM_CMD"   || fail=$((fail+1)) ;;
    besu)   run "$tag" "$target" "$ident" "$BESU_CMD"   || fail=$((fail+1)) ;;
    jmeter) run "$tag" "$target" "$ident" "$JMETER_CMD" || fail=$((fail+1)) ;;
    *)      run "$tag" "$target" "$ident" ""            || fail=$((fail+1)) ;;
  esac
done

log "----------------------------------------------------------"
if [[ $fail -eq 0 ]]; then
  log "all 5 hosts collected into $OUT"
else
  log "$fail host(s) failed — boot them and re-run (existing files are kept)"
fi
log "next: compare OS/docker/CPU across the 5 files to verify they really are identical"
