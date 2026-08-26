#!/bin/bash
# Usage: ./collect_app_logs.sh [variant]

set -euo pipefail

VARIANT="${1:-monolith}"
OUTFILE="./app_logs_${VARIANT}_$(date +%Y%m%d_%H%M%S).txt"
COMPOSE_FILE="docker-compose.${VARIANT}.yml"
SERVICE_NAME=""
SERVICE_NAMES=()

container_exists() {
  local name="$1"
  docker ps -a --format '{{.Names}}' | grep -Fxq "${name}"
}

resolve_container_name() {
  local candidate
  for candidate in "$@"; do
    if container_exists "${candidate}"; then
      echo "${candidate}"
      return 0
    fi
  done
  return 1
}

case "${VARIANT}" in
  faas)
    # On app VM we use the remote compose descriptor for FaaS.
    COMPOSE_FILE="docker-compose.faas.remote.yml"
    SERVICE_NAME="pdms-faas"
    ;;
  decentralized)
    COMPOSE_FILE="docker-compose.decentralized.yml"
    SERVICE_NAME="pdms-hybrid-decentralized"
    ;;
  monolith)
    SERVICE_NAME="pdms-monolith"
    ;;
  microservices)
    SERVICE_NAMES=(
      "pdms-person|pdms-microservice-person"
      "pdms-snapshot|pdms-microservice-snapshot"
      "pdms-consent|pdms-microservice-consent|pdms-consent-service"
    )
    ;;
  microservices-async)
    SERVICE_NAMES=(
      "pdms-person-async|pdms-microservice-person-async"
      "pdms-snapshot-async|pdms-microservice-snapshot-async"
      "pdms-consent-async|pdms-microservice-consent-async|pdms-consent-service-async"
    )
    ;;
esac

# Prefer compose logs when the compose file is valid on the host.
# If compose is misconfigured for this VM (for example depends_on infra not present),
# fall back to container logs so collection still succeeds.
if [[ "${VARIANT}" == "microservices" || "${VARIANT}" == "microservices-async" ]]; then
  # For microservices variants, always collect explicit logs from all 3 services.
  found_any=0
  : > "${OUTFILE}"
  for svc_group in "${SERVICE_NAMES[@]}"; do
    IFS='|' read -r -a candidates <<< "${svc_group}"
    if resolved="$(resolve_container_name "${candidates[@]}")"; then
      found_any=1
      {
        echo "===== ${resolved} ====="
        docker logs --timestamps "${resolved}" 2>&1
        echo
      } >> "${OUTFILE}"
    fi
  done
  if [[ "${found_any}" -ne 1 ]]; then
    {
      echo "no microservices containers were found for variant=${VARIANT}"
      echo "compose file: ${COMPOSE_FILE}"
      echo "expected groups: ${SERVICE_NAMES[*]}"
    } >> "${OUTFILE}"
    exit 1
  fi
elif ! docker compose -f "${COMPOSE_FILE}" logs --timestamps > "${OUTFILE}" 2>&1; then
  # Backward-compatible single-service fallback for non-microservices variants.
  if [[ -z "${SERVICE_NAMES[*]}" && -n "${SERVICE_NAME}" ]] && container_exists "${SERVICE_NAME}"; then
    docker logs --timestamps "${SERVICE_NAME}" > "${OUTFILE}" 2>&1
  else
    # keep compose error output in the artifact for troubleshooting
    {
      echo "compose log collection failed for variant=${VARIANT} and no fallback container was found"
      echo "compose file: ${COMPOSE_FILE}"
      [[ -n "${SERVICE_NAME}" ]] && echo "expected container: ${SERVICE_NAME}"
    } >> "${OUTFILE}"
    exit 1
  fi
fi

echo "$OUTFILE"
