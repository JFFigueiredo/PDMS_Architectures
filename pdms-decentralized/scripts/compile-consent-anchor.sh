#!/usr/bin/env bash
set -euo pipefail
R="$(cd "$(dirname "$0")/.." && pwd)"
C="$R/src/main/resources/contracts"
I=ethereum/solc:0.8.26-alpine
D="$(docker create --platform linux/amd64 --entrypoint /bin/sh "$I" -c "sleep 300")"
trap 'docker rm -f "$D" &>/dev/null || true' EXIT
docker start "$D" >/dev/null
docker exec "$D" mkdir -p /w/out
docker cp "$C/ConsentAnchor.sol" "$D:/w/ConsentAnchor.sol"
docker exec "$D" solc --bin --abi -o /w/out /w/ConsentAnchor.sol
docker cp "$D:/w/out/ConsentAnchor.bin" "$C/out/"
docker cp "$D:/w/out/ConsentAnchor.abi" "$C/out/"
