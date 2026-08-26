#!/bin/bash
# Collects JMeter .jtl result files — PURE BASH, NO DEPENDENCIES
# Usage: ./collect_jmeter_results.sh monolith mixed

set -euo pipefail

VARIANT="${1:-monolith}"
SCENARIO="${2:-mixed}"
OUTDIR="./${VARIANT}_${SCENARIO}_$(date +%Y%m%d_%H%M%S)"

mkdir -p "$OUTDIR"
echo "[JMeter VM] Collecting JMeter results to: $OUTDIR"

# Copy all .jtl files (one directory up to jmeter/scripts/results)
cp ../scripts/results/*.jtl "$OUTDIR/" 2>/dev/null

# Count files
FILE_COUNT=$(ls "$OUTDIR"/*.jtl 2>/dev/null | wc -l)
if [[ "$FILE_COUNT" -eq 0 ]]; then
  echo "ERROR - no .jtl files found in ../scripts/results for variant=${VARIANT} scenario=${SCENARIO}" >&2
  exit 1
fi
echo "OK - JMeter results ($FILE_COUNT .jtl files)"

echo "DONE - Output: $OUTDIR"
