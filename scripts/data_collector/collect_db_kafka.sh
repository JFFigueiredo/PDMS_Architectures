#!/bin/bash
# Collects database dump and Kafka messages — PURE BASH, NO DEPENDENCIES
# Usage: ./collect_db_kafka.sh monolith mixed 1745668800 1745671200

VARIANT="${1:-monolith}"
SCENARIO="${2:-mixed}"
OUTDIR="./db_kafka_${VARIANT}_${SCENARIO}_$(date +%Y%m%d_%H%M%S)"

mkdir -p "$OUTDIR"
echo "[DB+Kafka VM] Collecting database and Kafka to: $OUTDIR"

# 1. Database Dump
echo "Dumping PostgreSQL..."
docker exec -t pdms-postgres pg_dump -U pdms_user -Fc pdms > "$OUTDIR/db_dump.dump" 2>/dev/null
echo "OK - Database dump"

# 2. Kafka Messages (Async Only)
case "$VARIANT" in
  *async*)
    echo "Exporting Kafka messages..."
    for topic in pdms.persons pdms.consents pdms.snapshots; do
      topic_file=$(echo "$topic" | tr '.' '_')
      docker exec pdms-kafka kafka-console-consumer.sh \
        --bootstrap-server localhost:9092 \
        --topic "$topic" \
        --from-beginning \
        --timeout-ms 5000 \
        > "$OUTDIR/kafka_${topic_file}_messages.jsonl" 2>/dev/null
      MSGCOUNT=$(wc -l < "$OUTDIR/kafka_${topic_file}_messages.jsonl" 2>/dev/null || echo 0)
      echo "OK - Kafka $topic ($MSGCOUNT messages)"
    done
    ;;
esac

echo "DONE - Output: $OUTDIR"
