#!/usr/bin/env bash
# =============================================================================
# auto_collect.sh — fill in missing benchmark runs for one architecture.
#
# Scans data/<arch>/{mix,read,writte}/{1..5}; for every empty rep folder it
# runs the full cycle (restart -> JMeter -> collect -> validate -> place) and
# stops at the first failure, leaving everything on disk for inspection.
#
# Usage:
#   ./auto_collect.sh <architecture> [scenario] [rep|range]
#
# Examples:
#   ./auto_collect.sh microservices-async            # every gap, mix -> read -> writte
#   ./auto_collect.sh microservices-async mix        # only the mix scenario
#   ./auto_collect.sh microservices-async mix 1      # only mix rep 1
#   ./auto_collect.sh faas read 2-4                  # read reps 2,3,4
#
# Environment overrides:
#   BASE_URL      target app URL      (default http://<app-host>)
#   JMETER_SSH    ssh destination     (default jmeter_sec)
#   SETTLE_SECS   wait after restart  (default 15)
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)/data"

BASE_URL="${BASE_URL:-http://<app-host>}"
JMETER_SSH="${JMETER_SSH:-jmeter_sec}"
SETTLE_SECS="${SETTLE_SECS:-15}"
# ~/.bashrc adds /opt/jmeter/bin to PATH, but bashrc returns early for the
# non-interactive shell that `ssh host 'cmd'` creates, so jmeter is not found
# (exit 127). Put it on PATH explicitly for the remote command.
JMETER_HOME="${JMETER_HOME:-/opt/jmeter}"
# Load ladder. Passed through to the runner so it is set in one place and is
# self-documenting: each run's General file prints the "Steps :" line it used.
STEPS="${STEPS:-2 5 15 50}"
STEP_DURATION="${STEP_DURATION:-300}"
# One .jtl per step plus one for the discarded warm-up.
EXPECTED_JTL=$(( $(echo "$STEPS" | wc -w) + 1 ))
# pdms-faas and pdms-monolith are configured with hikari maximum-pool-size=150
# against a Postgres max_connections=100, and idle-timeout is 10 minutes, so idle
# JDBC connections outlive the load and starve pg_dump ("sorry, too many clients
# already") — the same failure that ruined 14/15 dumps in the old async campaign.
# Idle connections are dropped after the measurement window closes and before the
# collector runs, so this cannot influence any benchmark number.
DB_SSH="${DB_SSH:-dbandkafka_sec}"
PG_CONTAINER="${PG_CONTAINER:-pdms-postgres}"
PG_USER="${PG_USER:-pdms_user}"
PG_DB="${PG_DB:-pdms}"
SCENARIO_ORDER=(mix read writte)
MIN_FILES=177          # monolith baseline; newer runs add 3 kafka message files

# ----------------------------------------------------------------- arguments
FAST="${FAST:-0}"
ARGS=()
for a in "$@"; do
  case "$a" in
    --fast) FAST=1 ;;
    *) ARGS+=("$a") ;;
  esac
done
set -- "${ARGS[@]:-}"

if [[ $# -lt 1 || -z "${1:-}" ]]; then
  echo "Usage: $0 [--fast] <architecture> [scenario] [rep|range]" >&2
  exit 2
fi
ARCH="$1"
SCEN_FILTER="${2:-}"
REP_FILTER="${3:-}"

if [[ -n "$SCEN_FILTER" ]]; then
  case "$SCEN_FILTER" in
    mix|read|writte) SCENARIO_ORDER=("$SCEN_FILTER") ;;
    *) echo "ERROR: scenario must be mix, read or writte (got '$SCEN_FILTER')" >&2; exit 2 ;;
  esac
fi

REPS=(1 2 3 4 5)
if [[ -n "$REP_FILTER" ]]; then
  if   [[ "$REP_FILTER" =~ ^[1-5]$ ]];            then REPS=("$REP_FILTER")
  elif [[ "$REP_FILTER" =~ ^([1-5])-([1-5])$ ]];  then
    lo="${BASH_REMATCH[1]}"; hi="${BASH_REMATCH[2]}"
    [[ "$lo" -le "$hi" ]] || { echo "ERROR: bad range '$REP_FILTER'" >&2; exit 2; }
    REPS=(); for ((i=lo; i<=hi; i++)); do REPS+=("$i"); done
  else
    echo "ERROR: rep must be N or N-M within 1..5 (got '$REP_FILTER')" >&2; exit 2
  fi
fi

# Fast runs use 35s steps and are named *_fast_* / *_rh_fast_* / *_wh_fast_*.
# compare_reps.py drops _rh_fast_ and _wh_fast_ but NOT the step-load _fast_ form,
# so fast mix data placed in data/ would be analysed as if it were a real 300s run.
# Fast results therefore go to a separate tree and never touch campaign data.
if [[ "$FAST" == "1" ]]; then
  ARCH_ROOT="$DATA_ROOT/_fasttest/$ARCH"
  mkdir -p "$ARCH_ROOT"/{mix,read,writte}/{1,2,3,4,5}
else
  ARCH_ROOT="$DATA_ROOT/$ARCH"
fi
[[ -d "$ARCH_ROOT" ]] || { echo "ERROR: no data folder $ARCH_ROOT" >&2; exit 2; }

LOG_FILE="${SCRIPT_DIR}/campaign_${ARCH}_$(date +%Y%m%d_%H%M%S).log"

# ------------------------------------------------------------------ logging
log()  { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG_FILE"; }
warn() { log "WARN  : $*"; }
die()  { log "ERROR : $*"; log "STOPPED — nothing was deleted; inspect and re-run when fixed."; exit 1; }

# ------------------------------------------------- stale JMeter cleanup
# Killing a campaign leaves the *remote* runner script alive: it is a child of
# sshd, not of this script. Killing only the JMeter JVM makes that script move
# on to its next step and spawn a fresh JVM, so a zombie run keeps generating
# load against whatever app is deployed — silently contaminating the real run
# and writing extra .jtl files into the shared results directory.
# Order matters: kill the scripts first, then the JVMs.
# The [r] / [A] bracket trick stops the patterns matching this command itself.
# ---------------------------------------------------- readiness gate
# A fixed settle is wrong for multi-service architectures: monolith restarts in
# ~13s, but microservices boots three JVMs on a 2-core VM and consent-service
# alone reports "Started ... in 47.188 seconds". The runner health-checks
# immediately and aborts with HTTP 000 if a service is not listening yet, so
# poll every service until it answers 200 instead of guessing a duration.
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"
wait_for_health() {
  local ports="8080"
  [[ "$ARCH" == *microservices* ]] && ports="8080 8081 8082"
  local start=$SECONDS
  for port in $ports; do
    local code="" waited=0
    while true; do
      code=$(ssh -o BatchMode=yes -o ConnectTimeout=10 "$JMETER_SSH" \
        "curl -s -o /dev/null -w '%{http_code}' --max-time 5 $BASE_URL:$port/actuator/health" 2>/dev/null)
      [[ "$code" == "200" ]] && break
      waited=$((SECONDS - start))
      [[ "$waited" -ge "$HEALTH_TIMEOUT" ]] && \
        die "service on :$port not healthy after ${HEALTH_TIMEOUT}s (last HTTP $code) — refusing to start the run"
      sleep 5
    done
  done
  log "          all services healthy after $((SECONDS - start))s ($ports)"
}

kill_stale_jmeter() {
  local res
  res=$(ssh -o BatchMode=yes -o ConnectTimeout=25 "$JMETER_SSH" '
    before_s=$(ps -eo args | grep -cE "[r]un-(step-load|read-heavy|write-heavy)(-fast)?\.sh")
    before_j=$(ps -eo args | grep -c "[A]pacheJMeter")
    pkill -9 -f "[r]un-step-load.sh"   2>/dev/null
    pkill -9 -f "[r]un-read-heavy.sh"  2>/dev/null
    pkill -9 -f "[r]un-write-heavy.sh" 2>/dev/null
    sleep 1
    pkill -9 -f "[A]pacheJMeter" 2>/dev/null
    sleep 1
    jtl=$(ls ~/jmeter/scripts/results/*.jtl 2>/dev/null | wc -l)
    rm -f ~/jmeter/scripts/results/*.jtl 2>/dev/null
    after_s=$(ps -eo args | grep -cE "[r]un-(step-load|read-heavy|write-heavy)(-fast)?\.sh")
    after_j=$(ps -eo args | grep -c "[A]pacheJMeter")
    echo "$before_s $before_j $jtl $after_s $after_j"
  ' 2>/dev/null)
  set -- $res
  local bs="${1:-?}" bj="${2:-?}" jtl="${3:-?}" as="${4:-?}" aj="${5:-?}"
  if [[ "$bs" == "0" && "$bj" == "0" ]]; then
    log "          pre-flight: no stale JMeter processes (cleared $jtl leftover .jtl)"
  else
    warn "pre-flight: killed $bs stale runner script(s) and $bj JMeter JVM(s); cleared $jtl leftover .jtl"
  fi
  if [[ "$as" != "0" || "$aj" != "0" ]]; then
    die "could not clear stale JMeter processes (scripts left=$as, JVMs left=$aj) — a zombie run would contaminate this one"
  fi
}

runner_for() {
  local suffix=""
  [[ "$FAST" == "1" ]] && suffix="-fast"
  case "$1" in
    mix)    echo "run-step-load${suffix}.sh" ;;
    read)   echo "run-read-heavy${suffix}.sh" ;;
    writte) echo "run-write-heavy${suffix}.sh" ;;
  esac
}

# --------------------------------------------------------------- validation
# validate <dir> <label>  -> 0 ok, 1 failed (reasons printed)
validate() {
  local d="$1" label="$2" errs=0
  local n; n=$(find "$d" -type f 2>/dev/null | wc -l | tr -d ' ')
  [[ "$n" -ge "$MIN_FILES" ]] || { log "  FAIL $label: $n files (expected >= $MIN_FILES)"; errs=1; }

  if [[ -s "$d/run.log" ]]; then
    tail -1 "$d/run.log" | grep -q "Completed successfully" \
      || { log "  FAIL $label: run.log does not end with 'Completed successfully'"; errs=1; }
  else
    log "  FAIL $label: run.log missing or empty"; errs=1
  fi

  # Warning, not a failure: every archive collected before 2026-08-18 predates the
  # invocation record, and those must still validate.
  [[ -f "$d/invocation.txt" ]] \
    || warn "$label: no invocation.txt (archive predates the record, or the write failed)"

  if [[ -s "$d/General" ]]; then
    local n_steps; n_steps=$(echo "$STEPS" | wc -w | tr -d ' ')
    grep -q "All $n_steps steps complete" "$d/General" \
      || { log "  FAIL $label: General has no 'All $n_steps steps complete'"; errs=1; }
  else
    log "  FAIL $label: General missing or empty"; errs=1
  fi

  if [[ -f "$d/jmeter.zip" ]]; then
    unzip -tqq "$d/jmeter.zip" >/dev/null 2>&1 || { log "  FAIL $label: jmeter.zip is corrupt"; errs=1; }
    # List once into a variable: piping into `grep -q` makes grep exit early, which
    # SIGPIPEs unzip and — under `pipefail` — would look like a failure on a good zip.
    local listing; listing=$(unzip -l "$d/jmeter.zip" 2>/dev/null)
    local j; j=$(printf '%s\n' "$listing" | grep -c '\.jtl$')
    [[ "$j" -eq "$EXPECTED_JTL" ]] || { log "  FAIL $label: jmeter.zip holds $j .jtl files (expected $EXPECTED_JTL)"; errs=1; }
    case "$listing" in
      *WARMUP*) ;;
      *) log "  FAIL $label: jmeter.zip has no WARMUP phase"; errs=1 ;;
    esac
  else
    log "  FAIL $label: jmeter.zip missing"; errs=1
  fi

  local applog; applog=$(find "$d/app" -name 'app_logs_*' -type f 2>/dev/null | head -1)
  [[ -s "$applog" ]] || { log "  FAIL $label: app logs missing or empty"; errs=1; }

  local dump; dump=$(find "$d/dbAndKafka" -name 'db_dump.dump' -type f 2>/dev/null | head -1)
  if [[ -n "$dump" ]]; then
    file -b "$dump" | grep -qi 'PostgreSQL' \
      || { log "  FAIL $label: db_dump.dump is not a PostgreSQL dump ($(head -c 90 "$dump" | tr -d '\n'))"; errs=1; }
  else
    log "  FAIL $label: db_dump.dump missing"; errs=1
  fi

  local inv; inv=$(find "$d/dbAndKafka" -name 'topic_inventory_cloud.csv' -type f 2>/dev/null | head -1)
  if [[ -n "$inv" ]]; then
    local t; t=$(grep -c '^pdms\.' "$inv")
    [[ "$t" -ge 4 ]] || { log "  FAIL $label: topic inventory lists $t pdms topics (expected >= 4)"; errs=1; }
  else
    log "  FAIL $label: topic_inventory_cloud.csv missing"; errs=1
  fi

  # Besu got its own VM in 2026-08, so decentralized collects a fifth host bundle
  # (app, db, jmeter, prom, besu) plus the besu/ chain artifacts.
  local want_hosts=4
  [[ "$ARCH" == "decentralized" ]] && want_hosts=5
  local h; h=$(find "$d/hostMetrics" -maxdepth 1 -type d -name 'host_metrics_*' 2>/dev/null | wc -l | tr -d ' ')
  [[ "$h" -eq "$want_hosts" ]] || { log "  FAIL $label: $h hostMetrics host folders (expected $want_hosts)"; errs=1; }

  if [[ "$ARCH" == "decentralized" ]]; then
    [[ -d "$d/hostMetrics" ]] && find "$d/hostMetrics" -maxdepth 1 -type d -name 'host_metrics_besu_*' | grep -q . \
      || { log "  FAIL $label: no host_metrics_besu_* bundle"; errs=1; }
    find "$d/besu" -maxdepth 1 -type d -name 'besu_data_*' 2>/dev/null | grep -q . \
      || { log "  FAIL $label: no besu/besu_data_* artifact"; errs=1; }
  fi

  local p; p=$(find "$d/prometheus" -name '*.json' -type f 2>/dev/null | wc -l | tr -d ' ')
  [[ "$p" -ge 128 ]] || { log "  FAIL $label: $p prometheus JSON files (expected >= 128)"; errs=1; }
  find "$d/prometheus" -name 'queries_manifest.json' 2>/dev/null | grep -q . \
    || { log "  FAIL $label: queries_manifest.json missing"; errs=1; }

  # Known-broken, non-fatal: Kafka CLI absent on the broker container.
  if grep -lq 'executable file not found' "$d"/dbAndKafka/*/kafka_pdms_*_messages.jsonl 2>/dev/null; then
    warn "$label: kafka_pdms_*_messages.jsonl contain a shell error, not messages (kafka-console-consumer.sh missing on the broker)"
  fi

  [[ "$errs" -eq 0 ]]
}

# -------------------------------------------------------------- one full run
process() {
  local scen="$1" rep="$2"
  local dest="$ARCH_ROOT/$scen/$rep"
  local stage_name="${ARCH}${rep}${scen}"
  [[ "$FAST" == "1" ]] && stage_name="fasttest_${stage_name}"
  local stage="$SCRIPT_DIR/$stage_name"
  local runner; runner="$(runner_for "$scen")"
  local cmd="BASE_URL=$BASE_URL ./$runner $ARCH"
  local remote_cmd="export PATH=\"\$PATH:$JMETER_HOME/bin\"; cd ~/jmeter && STEPS=\"$STEPS\" STEP_DURATION=$STEP_DURATION $cmd"

  log "=============================================================="
  log "GAP: $ARCH/$scen/$rep   staging=$stage_name   runner=$runner"

  local reused=0
  if [[ -d "$stage" && -n "$(ls -A "$stage" 2>/dev/null)" ]]; then
    log "Staging folder already exists — skipping restart/JMeter, validating what is there."
    reused=1
  else
    log "STEP 1/6  restart_all_services.sh $ARCH"
    bash "$SCRIPT_DIR/restart_all_services.sh" "$ARCH" >>"$LOG_FILE" 2>&1 \
      || die "restart_all_services.sh failed (see $LOG_FILE)"

    log "STEP 2/6  waiting for services to become healthy"
    wait_for_health
    sleep "$SETTLE_SECS"

    if [[ "$FAST" == "1" ]]; then
      # The -fast runners have no [init] leftover-cleanup block, so stale .jtl
      # files would be folded into this run's archive. Clear the dir ourselves.
      log "          [fast] clearing ~/jmeter/scripts/results on the remote first"
      ssh -o BatchMode=yes "$JMETER_SSH" 'rm -f ~/jmeter/scripts/results/*.jtl' \
        || die "could not clear the remote results directory"
    fi
    kill_stale_jmeter
    log "STEP 3/6  JMeter on $JMETER_SSH: $cmd   ($([[ "$FAST" == "1" ]] && echo "~4 min, FAST" || echo "~30 min"))"
    local run_started_at; run_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    local gen_tmp="$SCRIPT_DIR/.General.${stage_name}.tmp"
    printf '<ssh-user>@Jmeter:~/jmeter$ %s\n' "$cmd" > "$gen_tmp"
    ssh -o BatchMode=yes -o ServerAliveInterval=60 -o ServerAliveCountMax=15 \
        "$JMETER_SSH" "$remote_cmd" 2>&1 | tee -a "$gen_tmp"
    local rc="${PIPESTATUS[0]}"
    [[ "$rc" -eq 0 ]] || die "JMeter run failed (ssh exit $rc); output kept at $gen_tmp"
    # The runner prints "All 4 steps complete." unconditionally, even when every
    # step died, so that banner alone proves nothing. Check for step errors too.
    local n_steps; n_steps=$(echo "$STEPS" | wc -w | tr -d ' ')
    grep -q "All $n_steps steps complete" "$gen_tmp" \
      || die "JMeter did not report 'All $n_steps steps complete'; output kept at $gen_tmp"
    if grep -q "ERROR: JMeter exited with code" "$gen_tmp"; then
      local codes; codes=$(grep -o "ERROR: JMeter exited with code [0-9]*" "$gen_tmp" | sort -u | tr '\n' ' ')
      [[ "$codes" == *"code 127"* ]] && log "      exit 127 means 'command not found' — jmeter is not on PATH at $JMETER_HOME/bin"
      die "JMeter steps failed ($codes); output kept at $gen_tmp"
    fi
    grep -q "^summary =" "$gen_tmp" \
      || die "JMeter produced no 'summary =' lines — no load was generated; output kept at $gen_tmp"

    # Guard against collect_all_remote_data.sh's silent fallback: when
    # ~/jmeter/scripts/results is empty it copies ~/jmeter/results instead,
    # which holds unrelated historical runs.
    local n_jtl
    n_jtl=$(ssh -o BatchMode=yes "$JMETER_SSH" 'ls ~/jmeter/scripts/results/*.jtl 2>/dev/null | wc -l' | tr -d ' ')
    [[ "$n_jtl" -eq "$EXPECTED_JTL" ]] \
      || die "expected $EXPECTED_JTL fresh .jtl in ~/jmeter/scripts/results, found $n_jtl — refusing to collect (the collector would fall back to ~/jmeter/results)"

    # The collector pulls large artifacts (faas app logs exceed 160 MB) and its
    # ssh transfers occasionally die with "Operation timed out / Broken pipe".
    # That is transient, so retry the collection once. A second failure stops the
    # run. Validation failures are never retried — only this network step.
    # Free Postgres connection slots so pg_dump can connect.
    local before after
    before=$(ssh -o BatchMode=yes -o ConnectTimeout=25 "$DB_SSH" \
      "docker exec $PG_CONTAINER psql -U $PG_USER -d $PG_DB -tAc 'select count(*) from pg_stat_activity'" 2>/dev/null | tr -d ' ')
    if [[ -n "$before" ]]; then
      ssh -o BatchMode=yes -o ConnectTimeout=25 "$DB_SSH" \
        "docker exec $PG_CONTAINER psql -U $PG_USER -d $PG_DB -tAc \"select pg_terminate_backend(pid) from pg_stat_activity where state='idle' and application_name='PostgreSQL JDBC Driver' and pid <> pg_backend_pid()\"" >/dev/null 2>&1
      after=$(ssh -o BatchMode=yes -o ConnectTimeout=25 "$DB_SSH" \
        "docker exec $PG_CONTAINER psql -U $PG_USER -d $PG_DB -tAc 'select count(*) from pg_stat_activity'" 2>/dev/null | tr -d ' ')
      log "          freed Postgres connections: $before -> ${after:-?} (limit 250)"
    else
      warn "could not reach $DB_SSH to free Postgres connections — pg_dump may fail"
    fi

    log "STEP 4/6  collect_all_remote_data.sh $stage_name $ARCH"
    if ! ( cd "$SCRIPT_DIR" && bash ./collect_all_remote_data.sh "$stage_name" "$ARCH" ) >>"$LOG_FILE" 2>&1; then
      log "          collection failed — retrying once in 30s (transient transfer errors are common here)"
      # A partial first attempt leaves artifacts behind; reusing the folder makes the
      # retry fail validation with duplicated hostMetrics host folders. Start clean.
      if [[ -d "$stage" ]]; then
        rm -rf "$stage"
        log "          cleared partial staging folder before retry"
      fi
      sleep 30
      ( cd "$SCRIPT_DIR" && bash ./collect_all_remote_data.sh "$stage_name" "$ARCH" ) >>"$LOG_FILE" 2>&1 \
        || die "collect_all_remote_data.sh failed twice (see $LOG_FILE)"
      log "          retry succeeded"
    fi

    [[ -d "$stage" ]] || die "collector did not create $stage"
    mv "$gen_tmp" "$stage/General" || die "could not place General into $stage"
    log "          General written into staging folder"

    # Record the invocation itself. General captures JMeter's OUTPUT; nothing in the
    # archive recorded the COMMAND, so a reuser could see the numbers but not the run
    # that produced them (the reported ladder was supplied through STEPS at invocation
    # and appeared nowhere in the deposit). Added 2026-08-18.
    #
    # Written only on the fresh-run path, and deliberately so: on the reuse path the
    # staging folder was produced by an EARLIER invocation, possibly with a different
    # ladder, so writing today's values there would record a command that did not
    # produce the archive. A missing record is recoverable; a plausible wrong one is
    # not. validate() warns when the file is absent.
    #
    # jmeter_ssh is deliberately NOT recorded: an override of the form user@host would
    # publish an SSH username and the load generator's address into a public deposit.
    local inv_tmp="$stage/.invocation.txt.tmp"
    if {
      printf 'variant=%s\nscenario=%s\nrepetition=%s\n' "$ARCH" "$scen" "$rep"
      printf 'run_started_at=%s\n' "$run_started_at"
      printf 'recorded_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf 'runner=%s\n' "$runner"
      printf 'steps=%s\n' "$STEPS"
      printf 'step_duration=%s\n' "$STEP_DURATION"
      printf 'base_url=%s\n' "$BASE_URL"
      printf 'jmeter_home=%s\n' "$JMETER_HOME"
      printf 'fast_mode=%s\n' "$FAST"
      printf 'runner_command=%s\n' "$cmd"
      printf 'remote_command=%s\n' "$remote_cmd"
    } > "$inv_tmp" && [[ -s "$inv_tmp" ]] && mv "$inv_tmp" "$stage/invocation.txt"; then
      log "          invocation.txt written into staging folder"
    else
      # Never fatal. Aborting a completed multi-hour run over a metadata file would
      # push the operator into re-running, which takes the reuse path and skips this
      # block for good.
      rm -f "$inv_tmp"
      warn "$ARCH/$scen/$rep: could not write invocation.txt; archive will have no invocation record"
    fi
  fi

  # collect_all_remote_data.sh sometimes leaves its temp archive behind. It is a
  # duplicate of jmeter.zip, so drop it before validating/copying rather than
  # propagating it into data/.
  local leftovers; leftovers=$(find "$stage" -maxdepth 1 -name '.jmeter.*.tgz' 2>/dev/null | wc -l | tr -d ' ')
  if [[ "$leftovers" -gt 0 ]]; then
    find "$stage" -maxdepth 1 -name '.jmeter.*.tgz' -delete
    log "          removed $leftovers leftover .jmeter.*.tgz temp archive(s) from staging"
  fi

  log "STEP 5/6  validating staging folder"
  if ! validate "$stage" "staging"; then
    if [[ "$reused" -eq 1 ]]; then
      log "      This staging folder was left over from an earlier attempt and is incomplete."
      log "      Delete it to force a fresh run:   rm -rf '$stage'"
    fi
    die "staging folder $stage failed validation"
  fi
  log "          staging OK ($(find "$stage" -type f | wc -l | tr -d ' ') files)"

  log "STEP 6/6  copying into $dest"
  mkdir -p "$dest"
  cp -R "$stage"/. "$dest"/ || die "copy to $dest failed"
  validate "$dest" "destination" || die "destination $dest failed validation after copy"
  log "          destination OK ($(find "$dest" -type f | wc -l | tr -d ' ') files) — staging kept at $stage"
  log "DONE: $ARCH/$scen/$rep"
}

# ---------------------------------------------------------------------- main
log "auto_collect starting — arch=$ARCH scenarios=${SCENARIO_ORDER[*]} reps=${REPS[*]}"
[[ "$FAST" == "1" ]] && log "*** FAST MODE — 35s steps. Results go to _fasttest/ and are NOT campaign data. ***"
log "data root : $ARCH_ROOT"
log "target    : $BASE_URL via $JMETER_SSH"
log "ladder    : threads=[$STEPS] x ${STEP_DURATION}s  (expecting $EXPECTED_JTL .jtl per run)"
log "log file  : $LOG_FILE"

gaps=0; done_count=0
for scen in "${SCENARIO_ORDER[@]}"; do
  for rep in "${REPS[@]}"; do
    d="$ARCH_ROOT/$scen/$rep"
    if [[ -d "$d" ]] && [[ "$(find "$d" -type f | wc -l | tr -d ' ')" -gt 0 ]]; then
      log "SKIP $ARCH/$scen/$rep — already populated"
      continue
    fi
    gaps=$((gaps + 1))
    process "$scen" "$rep"
    done_count=$((done_count + 1))
  done
done

log "=============================================================="
if [[ "$gaps" -eq 0 ]]; then
  log "Nothing to do — every selected rep already has data."
else
  log "Completed $done_count of $gaps gap(s) with no errors."
fi
