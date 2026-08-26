#!/usr/bin/env python3
import json
import os
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SOURCE_DASHBOARD = ROOT / "grafana" / "dashboards" / "pdms-dashboard.json"
TARGET_DASHBOARD = Path(__file__).resolve().parent / "grafana" / "dashboards" / "pdms-dashboard-influx.json"
METRICS_DIR = Path(
    os.getenv(
        "PROM_METRICS_DIR",
        str(ROOT / "RetriviedData" / "monolith" / "mix" / "1" / "prometheus" / "prometheus_metrics_20260427_153405")
    )
).resolve()
QUERY_DEFINITIONS = METRICS_DIR / "query_definitions.json"

INFLUX_DS = "InfluxDB-Historical"
BUCKET = "pdms_historical"
MEASUREMENT = "prometheus_metric_file"

PROMQL_KEYWORDS = {
    "sum", "rate", "max", "min", "avg", "by", "without", "count", "topk", "bottomk",
    "histogram_quantile", "irate", "increase", "delta", "clamp_max", "clamp_min",
    "abs", "round", "ln", "log2", "log10", "scalar", "vector", "and", "or", "unless",
    "on", "ignoring", "group_left", "group_right", "bool", "offset"
}


def detect_time_range_from_metrics_dir():
    min_ts = None
    max_ts = None

    for p in sorted(METRICS_DIR.glob("*.json")):
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not isinstance(doc, dict):
            continue
        data = doc.get("data")
        if not isinstance(data, dict):
            continue
        result = data.get("result")
        if not isinstance(result, list):
            continue

        for series in result:
            if not isinstance(series, dict):
                continue
            for point in series.get("values", []):
                if not isinstance(point, list) or len(point) < 1:
                    continue
                try:
                    ts = int(float(point[0]))
                except Exception:
                    continue
                if min_ts is None or ts < min_ts:
                    min_ts = ts
                if max_ts is None or ts > max_ts:
                    max_ts = ts

    if min_ts is None or max_ts is None:
        # Safe fallback if dataset has no points.
        return {"from": "now-1h", "to": "now"}
    return {"from": f"{min_ts * 1000}", "to": f"{max_ts * 1000}"}


def load_metric_name_index():
    index = {}
    for p in sorted(METRICS_DIR.glob("*.json")):
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not isinstance(doc, dict):
            continue
        data = doc.get("data")
        if not isinstance(data, dict):
            continue
        result = data.get("result")
        if not isinstance(result, list):
            continue
        for series in result:
            if not isinstance(series, dict):
                continue
            metric_name = series.get("metric", {}).get("__name__")
            if metric_name:
                index.setdefault(metric_name, set()).add(p.name)
    return index


def load_query_file_map():
    if not QUERY_DEFINITIONS.exists():
        return {}
    try:
        items = json.loads(QUERY_DEFINITIONS.read_text(encoding="utf-8"))
    except Exception:
        return {}

    file_map = {}
    for item in items:
        if not isinstance(item, dict):
            continue
        title = str(item.get("panel_title", ""))
        ref = str(item.get("ref_id", ""))
        query = str(item.get("query", ""))
        out = str(item.get("output_file", ""))
        if title and ref and query and out:
            file_map[(title, ref, query)] = out
    return file_map


def extract_metric_candidates(promql):
    # Grab identifier-like tokens and keep only likely metric names.
    tokens = re.findall(r"[a-zA-Z_:][a-zA-Z0-9_:]*", promql or "")
    out = []
    for t in tokens:
        if t in PROMQL_KEYWORDS:
            continue
        if t.startswith("status") or t.startswith("uri") or t.startswith("method"):
            continue
        if t.startswith("le"):
            continue
        out.append(t)
    return out


def panel_title_fallback_files(title):
    t = (title or "").lower()
    if "cpu" in t:
        return ["cpu_usage_pct.json", "system_cpu_usage.json", "jvm_cpu_usage.json", "metrics_cpu.json"]
    if "heap memory" in t:
        return ["jvm_heap_used.json", "jvm_heap_max.json", "jvm_heap_committed.json", "metrics_heap_used.json", "metrics_heap_max.json", "metrics_heap_committed.json"]
    if "non-heap" in t:
        return ["jvm_nonheap_used.json", "jvm_nonheap_max.json", "metrics_nonheap.json"]
    if "threads" in t:
        return ["jvm_threads_live.json", "jvm_threads_daemon.json", "jvm_threads_peak.json", "metrics_threads_active.json", "metrics_threads_daemon.json", "metrics_threads_peak.json"]
    if "gc" in t:
        return ["jvm_gc_pause_time.json", "jvm_gc_pause_count.json", "metrics_gc_pause_time.json", "metrics_gc_count.json"]
    if "class loading" in t:
        return ["jvm_classes_loaded.json", "metrics_classes_loaded.json"]
    if "process memory" in t:
        return ["process_resident_memory.json", "process_virtual_memory.json", "metrics_process_resident_memory.json", "metrics_process_virtual_memory.json"]
    if "request rate" in t or "total http request rate" in t:
        return ["http_rps_no_actuator.json", "http_rps.json", "metrics_http_total_rps.json"]
    if "success rate" in t:
        return ["http_success_percentage.json", "http_success_rate.json", "metrics_http_success_percentage.json", "metrics_success_rate.json"]
    if "5xx" in t:
        return ["http_5xx_percentage.json", "metrics_http_5xx_percentage.json"]
    if "4xx" in t:
        return ["http_4xx_percentage.json", "metrics_http_4xx_percentage.json"]
    if "latency" in t or "response time" in t:
        return ["http_mean_latency.json", "http_p95_latency.json", "http_p99_latency.json", "http_p50_latency.json", "metrics_http_mean_latency.json", "metrics_p95_latency.json", "metrics_p99_latency.json", "metrics_p50_latency.json"]
    if "kafka" in t:
        return ["kafka_producer_sent_total.json", "kafka_consumer_fetched.json", "kafka_consumer_lag.json", "metrics_kafka_producer_rate.json", "metrics_kafka_consumer_rate.json", "metrics_kafka_consumer_lag.json"]
    if "database" in t or "hikari" in t or "connection" in t:
        return [
            "db_connections_active.json",
            "db_connections_idle.json",
            "db_connections_pending.json",
            "db_connections_timeout.json",
            "metrics_db_connections_active.json",
            "metrics_db_connections_idle.json",
            "metrics_db_connections_pending.json",
            "metrics_db_connections_timeout.json"
        ]
    return []


def build_flux_query(files):
    if not files:
        return (
            f'from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEASUREMENT}")\n'
            f'  |> filter(fn: (r) => r._field == "value")'
        )

    file_filters = " or ".join([f'r.source_file == "{f}"' for f in sorted(set(files))])
    return (
        f'from(bucket: "{BUCKET}")\n'
        f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
        f'  |> filter(fn: (r) => r._measurement == "{MEASUREMENT}")\n'
        f'  |> filter(fn: (r) => r._field == "value")\n'
        f'  |> filter(fn: (r) => {file_filters})'
    )


def convert_target(target, title, metric_index, query_file_map):
    promql = target.get("expr", "")
    ref_id = target.get("refId", "A")

    # Exact parity mode: if this exact panel/ref/query was extracted,
    # bind directly to the corresponding exported file.
    mapped_file = query_file_map.get((title, ref_id, promql))
    if mapped_file:
        return {
            "refId": ref_id,
            "query": build_flux_query([mapped_file]),
        }

    files = set()

    for metric in extract_metric_candidates(promql):
        if metric in metric_index:
            files.update(metric_index[metric])

    if not files:
        files.update(panel_title_fallback_files(title))

    return {
        "refId": ref_id,
        "query": build_flux_query(sorted(files))
    }


def convert_panel(panel, metric_index, query_file_map):
    panel["datasource"] = INFLUX_DS
    targets = panel.get("targets", [])
    if targets:
        panel["targets"] = [
            convert_target(t, panel.get("title", ""), metric_index, query_file_map) for t in targets
        ]
    for key in ("alert",):
        panel.pop(key, None)
    return panel


def walk_panels(panels, metric_index, query_file_map):
    for p in panels:
        if "panels" in p and isinstance(p["panels"], list):
            walk_panels(p["panels"], metric_index, query_file_map)
        convert_panel(p, metric_index, query_file_map)


def main():
    metric_index = load_metric_name_index()
    query_file_map = load_query_file_map()
    dash = json.loads(SOURCE_DASHBOARD.read_text(encoding="utf-8"))
    dash["uid"] = "pdms-application-metrics-influx"
    dash["title"] = "PDMS Application Metrics (Influx Historical)"
    dash["version"] = int(dash.get("version", 1)) + 1
    dash["refresh"] = "30s"
    dash["time"] = detect_time_range_from_metrics_dir()
    walk_panels(dash.get("panels", []), metric_index, query_file_map)
    TARGET_DASHBOARD.parent.mkdir(parents=True, exist_ok=True)
    TARGET_DASHBOARD.write_text(json.dumps(dash, indent=2), encoding="utf-8")
    print(f"Generated {TARGET_DASHBOARD}")


if __name__ == "__main__":
    main()
