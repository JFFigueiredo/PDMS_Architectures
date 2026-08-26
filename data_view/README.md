# Data View (Influx + Grafana)

Simple setup to load historical metrics JSON into InfluxDB and visualize in Grafana.

## How it works

1. `import_historical_to_influx.py` reads JSON metric files (read-only).
2. It writes points into InfluxDB bucket `pdms_historical`.
3. Grafana (`docker-compose.grafana-only.yml`) uses datasource `InfluxDB-Historical`.
4. Dashboard `PDMS Application Metrics (Influx Historical)` shows the data.

## Run

```bash
cd data_view

# 1) Start InfluxDB
docker compose -f docker-compose.historical.yml up -d

# 2) Import data (choose your folder)
PROM_METRICS_DIR="../../data/monolith/mix/1/prometheus" python3 import_historical_to_influx.py

# 3) Regenerate/tune dashboard for that dataset (optional but recommended)
PROM_METRICS_DIR="../../data/monolith/mix/1/prometheus" python3 build_influx_dashboard.py
python3 tune_influx_dashboard.py

# 4) Start Grafana
docker compose -f docker-compose.grafana-only.yml up -d
```

Grafana: `http://127.0.0.1:3001`  
Login: `admin` / `admin`

## Extract all dashboard data from Prometheus

Use this to export everything needed to recreate `pdms-dashboard.json`:

```bash
cd data_view
chmod +x collect_prometheus_metrics.sh
./collect_prometheus_metrics.sh <START_TS> <END_TS> [STEP] [PROM_URL] [OUTDIR]
```

Example:

```bash
./collect_prometheus_metrics.sh 1777302000 1777305600 15s http://localhost:9090
```

This produces:
- one JSON output per panel target query
- `queries_manifest.json` (exact panel/query/file mapping)
- `quality_report.json` (finite vs NaN statistics)

## Stop

```bash
cd data_view
docker compose -f docker-compose.grafana-only.yml down
docker compose -f docker-compose.historical.yml down
```

## Collector naming contract

For cleaner future imports, use:

- `collector_output_contract.json` (stable output names)
- `queries_manifest.template.json` (query -> output mapping format)

These make dashboard/file mapping predictable.
