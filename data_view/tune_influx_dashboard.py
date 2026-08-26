#!/usr/bin/env python3
import json
import os
from pathlib import Path


DASH_PATH = Path(__file__).resolve().parent / "grafana" / "dashboards" / "pdms-dashboard-influx.json"
METRICS_DIR = Path(
    os.getenv(
        "PROM_METRICS_DIR",
        str(Path(__file__).resolve().parent.parent / "RetriviedData" / "Error" / "prometheus_metrics_monolith_20260427_175120")
    )
).resolve()
BUCKET = "pdms_historical"
MEAS = "prometheus_metric_file"


def q_for_file(file_name, extra=""):
    base = (
        f'from(bucket: "{BUCKET}")\n'
        f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
        f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
        f'  |> filter(fn: (r) => r._field == "value")\n'
        f'  |> filter(fn: (r) => r.source_file == "{file_name}")\n'
        f'  |> filter(fn: (r) => exists r._value and r._value >= 0.0)\n'
        f'  |> filter(fn: (r) => not exists r.uri or r.uri !~ /^\\/actuator.*/)\n'
        f'  |> drop(columns: ["_start", "_stop", "_measurement", "_field", "source_file", "series_index", "__name__", "instance", "job"])'
    )
    return base + (f"\n{extra}" if extra else "")


def pick_file(candidates):
    def file_has_points(path: Path) -> bool:
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return False
        results = doc.get("data", {}).get("result", [])
        for series in results:
            if series.get("values"):
                return True
        return False

    for name in candidates:
        p = METRICS_DIR / name
        if p.exists() and file_has_points(p):
            return name
    for name in candidates:
        if (METRICS_DIR / name).exists():
            return name
    # fallback to first candidate so config is still deterministic
    return candidates[0]


def file_has_metric_label(file_name, label):
    p = METRICS_DIR / file_name
    if not p.exists():
        return False
    try:
        doc = json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return False
    for series in doc.get("data", {}).get("result", []):
        metric = series.get("metric", {})
        if label in metric:
            return True
    return False


def file_exists(file_name):
    return (METRICS_DIR / file_name).exists()


def set_targets(panel, queries):
    panel["targets"] = [{"refId": chr(ord("A") + i), "query": q} for i, q in enumerate(queries)]


def set_unit(panel, unit):
    panel.setdefault("fieldConfig", {}).setdefault("defaults", {})["unit"] = unit


def set_refid_display_names(panel, names_by_refid):
    field_config = panel.setdefault("fieldConfig", {})
    overrides = field_config.setdefault("overrides", [])

    # Remove stale refId display-name overrides before writing deterministic ones.
    filtered = []
    for ov in overrides:
        matcher = ov.get("matcher", {})
        props = ov.get("properties", [])
        is_ref_override = matcher.get("id") == "byFrameRefID" and any(
            p.get("id") == "displayName" for p in props
        )
        if not is_ref_override:
            filtered.append(ov)

    for ref_id, display_name in names_by_refid.items():
        filtered.append(
            {
                "matcher": {"id": "byFrameRefID", "options": ref_id},
                "properties": [{"id": "displayName", "value": display_name}],
            }
        )

    field_config["overrides"] = filtered


def tune_panel(panel):
    title = panel.get("title", "")

    if title == "HTTP Max Response Time":
        set_targets(panel, [q_for_file(pick_file(["http_max_latency.json", "http_p999_latency.json", "metrics_p999_latency.json"]), '  |> group(columns: ["method", "uri"])')])
        set_refid_display_names(panel, {"A": "Max Response Time ${__field.labels.method} - ${__field.labels.uri}"})
    elif title == "HTTP Request Rate by Endpoint":
        set_targets(panel, [q_for_file(pick_file(["http_rps_by_endpoint.json", "http_rps_no_actuator.json", "metrics_rps.json"]), '  |> group(columns: ["method", "uri"])')])
        set_refid_display_names(panel, {"A": "${__field.labels.method} ${__field.labels.uri}"})
    elif title == "HTTP Mean Response Time":
        set_targets(panel, [q_for_file(pick_file(["http_mean_latency.json", "http_p50_latency.json", "metrics_http_mean_latency.json"]), '  |> group(columns: ["method", "uri"])')])
        set_unit(panel, "s")
        set_refid_display_names(panel, {"A": "Mean Response Time ${__field.labels.method} - ${__field.labels.uri}"})
    elif title == "HTTP Response Time Percentiles":
        set_targets(panel, [
            q_for_file(pick_file(["http_p99_latency.json", "metrics_p99_latency.json"]), '  |> group(columns: ["method", "uri"])'),
            q_for_file(pick_file(["http_p95_latency.json", "metrics_p95_latency.json"]), '  |> group(columns: ["method", "uri"])'),
            q_for_file(pick_file(["http_p50_latency.json", "metrics_p50_latency.json"]), '  |> group(columns: ["method", "uri"])')
        ])
        set_refid_display_names(
            panel,
            {
                "A": "p99 - ${__field.labels.method} ${__field.labels.uri}",
                "B": "p95 - ${__field.labels.method} ${__field.labels.uri}",
                "C": "p50 - ${__field.labels.method} ${__field.labels.uri}",
            },
        )
    elif title == "Response time total":
        mean_file = pick_file(["http_mean_latency.json", "http_p50_latency.json", "metrics_http_mean_latency.json"])
        p95_file = pick_file(["http_p95_latency.json", "metrics_p95_latency.json"])
        p99_file = pick_file(["http_p99_latency.json", "metrics_p99_latency.json"])
        sum_rate_file = "http_latency_sum_rate_no_actuator.json"
        count_rate_file = "http_latency_count_rate_no_actuator.json"

        def direct_global_average_query():
            return (
                f'sum_rate = from(bucket: "{BUCKET}")\n'
                f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
                f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
                f'  |> filter(fn: (r) => r._field == "value")\n'
                f'  |> filter(fn: (r) => r.source_file == "{sum_rate_file}")\n'
                '  |> filter(fn: (r) => exists r._value and r._value >= 0.0)\n'
                '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)\n'
                '  |> group(columns: ["_time"])\n'
                '  |> sum(column: "_value")\n'
                '  |> keep(columns: ["_time", "_value"])\n\n'
                f'count_rate = from(bucket: "{BUCKET}")\n'
                f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
                f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
                f'  |> filter(fn: (r) => r._field == "value")\n'
                f'  |> filter(fn: (r) => r.source_file == "{count_rate_file}")\n'
                '  |> filter(fn: (r) => exists r._value and r._value >= 0.0)\n'
                '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)\n'
                '  |> group(columns: ["_time"])\n'
                '  |> sum(column: "_value")\n'
                '  |> keep(columns: ["_time", "_value"])\n\n'
                'join(tables: {s: sum_rate, c: count_rate}, on: ["_time"], method: "inner")\n'
                '  |> filter(fn: (r) => r._value_c > 0.0)\n'
                '  |> map(fn: (r) => ({_time: r._time, _value: r._value_s / r._value_c}))\n'
                '  |> keep(columns: ["_time", "_value"])\n'
                '  |> group()\n'
                '  |> sort(columns: ["_time"])'
            )

        def weighted_global_mean_query(latency_file):
            return (
                f'lat = from(bucket: "{BUCKET}")\n'
                f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
                f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
                f'  |> filter(fn: (r) => r._field == "value")\n'
                f'  |> filter(fn: (r) => r.source_file == "{latency_file}")\n'
                '  |> filter(fn: (r) => exists r._value and r._value >= 0.0)\n'
                '  |> filter(fn: (r) => r.uri !~ /^\\/actuator.*/)\n'
                '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)\n'
                '  |> keep(columns: ["_time", "_value", "method", "uri", "status", "instance", "job"])\n\n'
                f'rps = from(bucket: "{BUCKET}")\n'
                f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
                f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
                f'  |> filter(fn: (r) => r._field == "value")\n'
                '  |> filter(fn: (r) => r.source_file == "http_rps.json" or r.source_file == "http_rps_no_actuator.json")\n'
                '  |> filter(fn: (r) => exists r._value and r._value >= 0.0)\n'
                '  |> filter(fn: (r) => r.uri !~ /^\\/actuator.*/)\n'
                '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)\n'
                '  |> keep(columns: ["_time", "_value", "method", "uri", "status", "instance", "job"])\n\n'
                'j = join(tables: {lat: lat, rps: rps}, on: ["_time", "method", "uri", "status", "instance", "job"], method: "inner")\n'
                '  |> map(fn: (r) => ({\n'
                '      _time: r._time,\n'
                '      weighted: r._value_lat * r._value_rps,\n'
                '      weight: r._value_rps\n'
                '  }))\n\n'
                'ws = j\n'
                '  |> group(columns: ["_time"])\n'
                '  |> sum(column: "weighted")\n'
                '  |> rename(columns: {weighted: "weighted_sum"})\n'
                '  |> keep(columns: ["_time", "weighted_sum"])\n\n'
                'wt = j\n'
                '  |> group(columns: ["_time"])\n'
                '  |> sum(column: "weight")\n'
                '  |> rename(columns: {weight: "weight_sum"})\n'
                '  |> keep(columns: ["_time", "weight_sum"])\n\n'
                'join(tables: {ws: ws, wt: wt}, on: ["_time"], method: "inner")\n'
                '  |> filter(fn: (r) => r.weight_sum > 0.0)\n'
                '  |> map(fn: (r) => ({_time: r._time, _value: r.weighted_sum / r.weight_sum}))\n'
                '  |> keep(columns: ["_time", "_value"])\n'
                '  |> group()\n'
                '  |> sort(columns: ["_time"])'
            )

        def endpoint_quantile_max_query(latency_file):
            return (
                f'from(bucket: "{BUCKET}")\n'
                f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
                f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
                f'  |> filter(fn: (r) => r._field == "value")\n'
                f'  |> filter(fn: (r) => r.source_file == "{latency_file}")\n'
                '  |> filter(fn: (r) => exists r._value and r._value >= 0.0)\n'
                '  |> filter(fn: (r) => exists r.method and exists r.uri)\n'
                '  |> filter(fn: (r) => r.uri !~ /^\\/actuator.*/)\n'
                '  |> group(columns: ["method", "uri"])\n'
                '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)\n'
                '  |> group(columns: ["_time"])\n'
                '  |> max(column: "_value")\n'
                '  |> keep(columns: ["_time", "_value"])\n'
                '  |> group()\n'
                '  |> sort(columns: ["_time"])'
            )

        # If latency files are endpoint-level (method/uri labels), compute one global line
        # weighted by endpoint RPS. If already global, use direct file query.
        if file_exists(sum_rate_file) and file_exists(count_rate_file):
            mean_query = direct_global_average_query()
        elif file_has_metric_label(mean_file, "uri"):
            mean_query = weighted_global_mean_query(mean_file)
        else:
            mean_query = q_for_file(mean_file, "  |> group()")

        if file_has_metric_label(p95_file, "uri"):
            p95_query = endpoint_quantile_max_query(p95_file)
        else:
            p95_query = q_for_file(p95_file, "  |> group()")

        if file_has_metric_label(p99_file, "uri"):
            p99_query = endpoint_quantile_max_query(p99_file)
        else:
            p99_query = q_for_file(p99_file, "  |> group()")

        set_targets(panel, [mean_query, p95_query, p99_query])
        set_unit(panel, "s")
        set_refid_display_names(
            panel,
            {
                "A": "average",
                "B": "p95",
                "C": "p99",
            },
        )
    elif title == "HTTP Status Codes":
        source_file = pick_file(["http_rps_by_status.json", "metrics_http_rps_by_status.json"])
        set_targets(panel, [
            q_for_file(source_file, '  |> filter(fn: (r) => r.status =~ /^2../)\n  |> group(columns: ["status"])'),
            q_for_file(source_file, '  |> filter(fn: (r) => r.status =~ /^4../)\n  |> group(columns: ["status"])'),
            q_for_file(source_file, '  |> filter(fn: (r) => r.status =~ /^5../)\n  |> group(columns: ["status"])'),
        ])
    elif title == "HTTP Success Rate":
        # Rebuild success percentage from status-series data, matching original PromQL intent:
        # (sum(rate(2xx)) / sum(rate(all))) * 100
        total_file = pick_file(["http_rps_no_actuator.json", "metrics_http_total_rps.json"])
        by_status_file = pick_file(["http_rps_by_status.json", "metrics_http_rps_by_status.json"])
        query = (
            f'total = from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            f'  |> filter(fn: (r) => r.source_file == "{total_file}")\n'
            "  |> group()\n"
            "  |> keep(columns: [\"_time\", \"_value\"])\n\n"
            f'ok = from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            f'  |> filter(fn: (r) => r.source_file == "{by_status_file}")\n'
            '  |> filter(fn: (r) => r.status =~ /^2../)\n'
            "  |> group(columns: [\"_time\"])\n"
            "  |> sum(column: \"_value\")\n"
            "  |> keep(columns: [\"_time\", \"_value\"])\n\n"
            "join(tables: {ok: ok, total: total}, on: [\"_time\"], method: \"inner\")\n"
            "  |> filter(fn: (r) => r._value_total > 0.0)\n"
            "  |> map(fn: (r) => ({\n"
            "      _time: r._time,\n"
            "      _value: (r._value_ok / r._value_total) * 100.0\n"
            "  }))"
        )
        set_targets(panel, [query])
        set_unit(panel, "percent")
    elif title == "Total HTTP Request Rate":
        set_targets(panel, [
            q_for_file(
                pick_file(["http_rps_no_actuator.json", "metrics_http_total_rps.json"]),
                "  |> group()\n"
                "  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)"
            )
        ])
    elif title == "Request Rate by HTTP Method":
        set_targets(panel, [
            q_for_file(
                pick_file(["http_rps_by_method.json", "metrics_http_total_rps.json"]),
                '  |> group(columns: ["method"])'
            )
        ])
    elif title == "Response Time p99 by HTTP Method":
        set_targets(panel, [
            q_for_file(
                pick_file(["http_p99_latency.json", "http_max_latency.json", "metrics_p99_latency.json"]),
                '  |> filter(fn: (r) => r.method == "GET")\n'
                '  |> group(columns: ["method", "uri"])\n'
                '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)'
            ),
            q_for_file(
                pick_file(["http_p99_latency.json", "http_max_latency.json", "metrics_p99_latency.json"]),
                '  |> filter(fn: (r) => r.method == "POST")\n'
                '  |> group(columns: ["method", "uri"])\n'
                '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)'
            ),
            q_for_file(
                pick_file(["http_p99_latency.json", "http_max_latency.json", "metrics_p99_latency.json"]),
                '  |> filter(fn: (r) => r.method == "PUT")\n'
                '  |> group(columns: ["method", "uri"])\n'
                '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)'
            ),
            q_for_file(
                pick_file(["http_p99_latency.json", "http_max_latency.json", "metrics_p99_latency.json"]),
                '  |> filter(fn: (r) => r.method == "DELETE")\n'
                '  |> group(columns: ["method", "uri"])\n'
                '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)'
            )
        ])
        set_unit(panel, "s")
    elif title == "Response Time p95 by HTTP Method":
        set_targets(panel, [
            q_for_file(
                pick_file(["http_p95_latency.json", "http_max_latency.json", "metrics_p95_latency.json"]),
                '  |> filter(fn: (r) => r.method == "GET")\n'
                '  |> group(columns: ["method", "uri"])'
            ),
            q_for_file(
                pick_file(["http_p95_latency.json", "http_max_latency.json", "metrics_p95_latency.json"]),
                '  |> filter(fn: (r) => r.method == "POST")\n'
                '  |> group(columns: ["method", "uri"])'
            ),
            q_for_file(
                pick_file(["http_p95_latency.json", "http_max_latency.json", "metrics_p95_latency.json"]),
                '  |> filter(fn: (r) => r.method == "PUT")\n'
                '  |> group(columns: ["method", "uri"])'
            ),
            q_for_file(
                pick_file(["http_p95_latency.json", "http_max_latency.json", "metrics_p95_latency.json"]),
                '  |> filter(fn: (r) => r.method == "DELETE")\n'
                '  |> group(columns: ["method", "uri"])'
            )
        ])
        set_unit(panel, "s")
    elif title == "Database Connection Pool - Limits":
        active_file = pick_file(["db_connections_active.json"])
        idle_file = pick_file(["db_connections_idle.json"])
        usage_query = (
            f'from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            f'  |> filter(fn: (r) => r.source_file == "{active_file}" or r.source_file == "{idle_file}")\n'
            '  |> map(fn: (r) => ({r with metric: "pool_usage"}))\n'
            '  |> group(columns: ["_time", "metric"])\n'
            "  |> sum(column: \"_value\")\n"
            '  |> group(columns: ["metric"])'
        )
        set_targets(panel, [
            q_for_file(pick_file(["db_connections_max.json"]), '  |> map(fn: (r) => ({r with metric: "max"}))'),
            q_for_file(pick_file(["db_connections_min.json"]), '  |> map(fn: (r) => ({r with metric: "min"}))'),
            usage_query,
        ])
        set_unit(panel, "short")
    elif title == "CPU Usage":
        cpu_query = (
            f'from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            '  |> filter(fn: (r) => r.source_file == "cpu_usage_pct.json" or r.source_file == "system_cpu_usage.json" or r.source_file == "jvm_cpu_usage.json" or r.source_file == "metrics_cpu.json")\n'
            '  |> filter(fn: (r) => exists r._value and r._value >= 0.0)\n'
            '  |> map(fn: (r) => ({\n'
            '      _time: r._time,\n'
            '      _value: if r.source_file == "jvm_cpu_usage.json" then r._value * 100.0 else r._value,\n'
            '      metric: "CPU Usage %"\n'
            '  }))\n'
            '  |> group(columns: ["metric"])\n'
            '  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)'
        )
        set_targets(panel, [cpu_query])
        set_unit(panel, "percent")
    elif title == "Consent Revokes per Minute":
        set_targets(panel, [
            q_for_file(
                pick_file(["pdms_consent_revoke.json"]),
                ""
            )
        ])
    elif title == "Consent Grants per Minute":
        set_targets(panel, [
            q_for_file(
                pick_file(["pdms_consent_grant.json"]),
                ""
            )
        ])
    elif title == "Privacy Policy Acceptance Rate":
        set_targets(panel, [
            q_for_file(
                pick_file(["pdms_privacy_policy_acceptance.json"]),
                ""
            )
        ])
    elif title == "Data Export Requests per Minute":
        set_targets(panel, [
            q_for_file(
                pick_file(["pdms_data_export_requests.json"]),
                "  |> derivative(unit: 1s, nonNegative: true)"
            )
        ])
    elif title == "Data Deletion Requests per Minute":
        set_targets(panel, [
            q_for_file(
                pick_file(["pdms_data_deletion_requests.json"]),
                "  |> derivative(unit: 1s, nonNegative: true)"
            )
        ])
    elif title == "HTTP Server Errors (5xx) by Endpoint":
        set_targets(panel, [
            q_for_file(
                pick_file(["http_5xx_by_endpoint.json", "http_5xx_percentage.json", "metrics_http_5xx_percentage.json"]),
                '  |> group(columns: ["method", "uri"])'
            )
        ])
    elif title == "HTTP Client Errors (4xx) by Endpoint":
        set_targets(panel, [
            q_for_file(
                pick_file(["http_4xx_by_endpoint.json", "http_4xx_percentage.json", "metrics_http_4xx_percentage.json"]),
                '  |> group(columns: ["method", "uri"])'
            )
        ])
    elif title == "HTTP Error Summary":
        set_targets(panel, [
            q_for_file(
                pick_file(["http_rps_by_status.json"]),
                '  |> filter(fn: (r) => r.status =~ /^5../)\n'
                '  |> group(columns: ["_time"])\n'
                '  |> sum(column: "_value")'
            ),
            q_for_file(
                pick_file(["http_rps_by_status.json"]),
                '  |> filter(fn: (r) => r.status =~ /^4../)\n'
                '  |> group(columns: ["_time"])\n'
                '  |> sum(column: "_value")'
            ),
            q_for_file(
                pick_file(["http_rps_by_status.json"]),
                '  |> filter(fn: (r) => r.status =~ /^2../)\n'
                '  |> group(columns: ["_time"])\n'
                '  |> sum(column: "_value")'
            ),
        ])
        set_unit(panel, "reqps")
    elif title == "Database Connection Pool - Timeouts & Creation Time":
        set_targets(panel, [
            q_for_file(pick_file(["db_connections_timeout.json"])),
            q_for_file(pick_file(["db_connection_creation_mean.json"])),
        ])
    elif title == "Database Connection Usage Time":
        set_targets(panel, [
            q_for_file(pick_file(["db_connection_usage_mean.json"])),
            q_for_file(pick_file(["db_connection_usage_p95.json"])),
        ])
    elif title == "Database Query Execution Time (p95)":
        set_targets(panel, [q_for_file(pick_file(["db_query_time_p95.json"]))])
    elif title == "Database Slow Queries (> 1s)":
        set_targets(panel, [q_for_file(pick_file(["db_slow_queries.json"]))])
    elif title == "Database Transaction Rollback Rate":
        set_targets(panel, [q_for_file(pick_file(["db_transaction_rollbacks.json"]))])
    elif title == "Database Connection Acquire Metrics":
        set_targets(panel, [
            q_for_file(pick_file(["db_connection_acquire_count.json"])),
            q_for_file(pick_file(["db_connection_acquire_mean.json"])),
        ])
    elif title == "Kafka Producer - Records Sent":
        set_targets(panel, [q_for_file(pick_file(["kafka_producer_sent.json"]))])
    elif title == "Kafka Consumer - Records Fetched":
        set_targets(panel, [q_for_file(pick_file(["kafka_consumer_fetched.json"]))])
    elif title == "Kafka Producer - Send Failures":
        set_targets(panel, [q_for_file(pick_file(["kafka_producer_failures.json"]))])
    elif title == "Kafka Producer/Consumer Latency":
        set_targets(panel, [
            q_for_file(pick_file(["kafka_producer_send_mean.json"])),
            q_for_file(pick_file(["kafka_template_latency_p95.json"])),
        ])
    elif title == "Kafka Consumer Lag":
        set_targets(panel, [q_for_file(pick_file(["kafka_consumer_lag.json"]), '  |> group(columns: ["topic"])')])
    elif title == "Kafka Producer - Send Latency Percentiles":
        set_targets(panel, [
            q_for_file(pick_file(["kafka_producer_send_mean.json"])),
            q_for_file(pick_file(["kafka_producer_send_p95.json"])),
            q_for_file(pick_file(["kafka_producer_send_p99.json"])),
        ])
    elif title == "Kafka Messages Published by Topic":
        topic_file = pick_file(["kafka_messages_by_topic.json"])
        set_targets(panel, [
            q_for_file(topic_file, '  |> filter(fn: (r) => r.topic == "pdms.person.change")\n  |> group(columns: ["topic"])'),
            q_for_file(topic_file, '  |> filter(fn: (r) => r.topic == "pdms.consent.change")\n  |> group(columns: ["topic"])'),
            q_for_file(topic_file, '  |> filter(fn: (r) => r.topic == "pdms.person.snapshot")\n  |> group(columns: ["topic"])'),
            q_for_file(topic_file, '  |> filter(fn: (r) => r.topic == "pdms.audit.events")\n  |> group(columns: ["topic"])'),
        ])
    elif title == "Kafka Producer - Total Messages Published (Cumulative)":
        set_targets(panel, [q_for_file(pick_file(["kafka_producer_sent_total.json"]))])
    elif title == "Kafka Producer - Success vs Failure Rate":
        set_targets(panel, [
            q_for_file(pick_file(["kafka_template_success.json"])),
            q_for_file(pick_file(["kafka_template_failures.json"])),
        ])
    elif title == "Kafka Consumer - Records Fetched by Topic":
        set_targets(panel, [q_for_file(pick_file(["kafka_consumer_fetched_topic.json"]), '  |> group(columns: ["topic"])')])
    elif title == "Kafka Consumer Lag by Topic":
        set_targets(panel, [q_for_file(pick_file(["kafka_consumer_lag.json"]), '  |> group(columns: ["topic"])')])
    elif title == "Kafka Producer - Throughput by Topic (Messages/sec)":
        set_targets(panel, [q_for_file(pick_file(["kafka_throughput_by_topic.json"]), '  |> group(columns: ["topic"])')])
    elif title == "Kafka Producer - Error Rate Percentage":
        set_targets(panel, [q_for_file(pick_file(["kafka_producer_error_pct.json"]))])
    elif title == "Kafka Consumer Commit Failures":
        set_targets(panel, [q_for_file(pick_file(["kafka_consumer_commit_failures.json"]))])
    elif title == "Kafka Consumer Errors":
        set_targets(panel, [q_for_file(pick_file(["kafka_consumer_errors.json"]))])
    elif title == "Kafka Template - Send Latency (p95)":
        set_targets(panel, [q_for_file(pick_file(["kafka_template_latency_p95.json"]))])
    elif title == "Kafka Template - Send Latency (p99)":
        set_targets(panel, [q_for_file(pick_file(["kafka_template_latency_p99.json"]))])
    elif title == "Kafka Template - Messages Sent (Success)":
        set_targets(panel, [q_for_file(pick_file(["kafka_template_success.json", "kafka_template_total.json"]))])
        set_unit(panel, "percent")
    elif title == "GC Pause Time & Rate":
        set_targets(panel, [
            q_for_file(pick_file(["jvm_gc_pause_time.json"])),
            q_for_file(pick_file(["jvm_gc_pause_count.json"])),
        ])
    elif title == "GC Collection Count Rate":
        set_targets(panel, [q_for_file(pick_file(["jvm_gc_pause_count.json"]))])
    elif title == "JVM Class Loading":
        set_targets(panel, [
            q_for_file(pick_file(["jvm_classes_loaded.json"]), "  |> derivative(unit: 1s, nonNegative: true)"),
            q_for_file(pick_file(["jvm_classes_unloaded.json"]), "  |> derivative(unit: 1s, nonNegative: true)"),
            q_for_file(pick_file(["jvm_classes_loaded.json"])),
        ])
    elif title == "Heap Memory Utilization Percentage":
        used_file = pick_file(["jvm_heap_used.json"])
        max_file = pick_file(["jvm_heap_max.json"])
        util_q = (
            f'u = from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            f'  |> filter(fn: (r) => r.source_file == "{used_file}")\n'
            "  |> keep(columns: [\"_time\", \"_value\", \"id\"])\n\n"
            f'm = from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            f'  |> filter(fn: (r) => r.source_file == "{max_file}")\n'
            "  |> keep(columns: [\"_time\", \"_value\", \"id\"])\n\n"
            "join(tables: {u: u, m: m}, on: [\"_time\", \"id\"], method: \"inner\")\n"
            "  |> map(fn: (r) => ({_time: r._time, _value: (r._value_u / r._value_m) * 100.0, id: r.id}))\n"
            "  |> group(columns: [\"id\"])"
        )
        set_targets(panel, [util_q])
        set_unit(panel, "percent")
    elif title == "JVM Heap Memory":
        set_targets(panel, [
            q_for_file(
                pick_file(["jvm_heap_used.json"]),
                '  |> group(columns: ["id"])'
            ),
            q_for_file(
                pick_file(["jvm_heap_max.json"]),
                '  |> filter(fn: (r) => r._value >= 0.0)\n'
                '  |> group(columns: ["id"])'
            ),
            q_for_file(
                pick_file(["jvm_heap_committed.json"]),
                '  |> group(columns: ["id"])'
            ),
        ])
        set_unit(panel, "bytes")
    elif title == "JVM Non-Heap Memory":
        set_targets(panel, [
            q_for_file(
                pick_file(["jvm_nonheap_used.json"]),
                '  |> group(columns: ["id"])'
            ),
            q_for_file(
                pick_file(["jvm_nonheap_max.json"]),
                '  |> filter(fn: (r) => r._value >= 0.0)\n'
                '  |> group(columns: ["id"])'
            ),
        ])
        set_unit(panel, "bytes")
    elif title == "JVM Threads":
        set_targets(panel, [
            q_for_file(pick_file(["jvm_threads_live.json"])),
            q_for_file(pick_file(["jvm_threads_daemon.json"])),
            q_for_file(pick_file(["jvm_threads_peak.json"])),
        ])
        set_unit(panel, "short")
    elif title == "JVM Thread States":
        states_file = pick_file(["jvm_threads_states.json"])
        set_targets(panel, [
            q_for_file(states_file, '  |> filter(fn: (r) => r.state == "runnable")'),
            q_for_file(states_file, '  |> filter(fn: (r) => r.state == "blocked")'),
            q_for_file(states_file, '  |> filter(fn: (r) => r.state == "waiting")'),
            q_for_file(states_file, '  |> filter(fn: (r) => r.state == "timed-waiting")'),
        ])
        set_unit(panel, "short")
    elif title == "JVM Memory Pools (G1 GC)":
        max_file = pick_file(["jvm_heap_max.json"])
        set_targets(panel, [
            q_for_file(pick_file(["jvm_eden_used.json"]), '  |> filter(fn: (r) => r.id == "G1 Eden Space")'),
            q_for_file(max_file, '  |> filter(fn: (r) => r.id == "G1 Eden Space")'),
            q_for_file(pick_file(["jvm_survivor_used.json"]), '  |> filter(fn: (r) => r.id == "G1 Survivor Space")'),
            q_for_file(pick_file(["jvm_old_gen_used.json"]), '  |> filter(fn: (r) => r.id == "G1 Old Gen")'),
            q_for_file(max_file, '  |> filter(fn: (r) => r.id == "G1 Old Gen")'),
        ])
        set_unit(panel, "bytes")
    elif title == "Process Memory":
        set_targets(panel, [
            q_for_file(pick_file(["process_resident_memory.json"])),
            q_for_file(pick_file(["process_virtual_memory.json"])),
        ])
    elif title == "Heap Memory: Committed vs Used vs Free":
        committed_file = pick_file(["jvm_heap_committed.json"])
        used_file = pick_file(["jvm_heap_used.json"])
        committed_q = (
            f'from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            f'  |> filter(fn: (r) => r.source_file == "{committed_file}")\n'
            "  |> group(columns: [\"_time\"])\n"
            "  |> sum(column: \"_value\")\n"
            "  |> map(fn: (r) => ({_time: r._time, _value: r._value, metric: \"Heap Committed\"}))\n"
            "  |> group(columns: [\"metric\"])"
        )
        used_q = (
            f'from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            f'  |> filter(fn: (r) => r.source_file == "{used_file}")\n'
            "  |> group(columns: [\"_time\"])\n"
            "  |> sum(column: \"_value\")\n"
            "  |> map(fn: (r) => ({_time: r._time, _value: r._value, metric: \"Heap Used\"}))\n"
            "  |> group(columns: [\"metric\"])"
        )
        free_q = (
            f'c = from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            f'  |> filter(fn: (r) => r.source_file == "{committed_file}")\n'
            '  |> group(columns: ["_time"])\n'
            '  |> sum(column: "_value")\n'
            '  |> keep(columns: ["_time", "_value"])\n\n'
            f'u = from(bucket: "{BUCKET}")\n'
            f'  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)\n'
            f'  |> filter(fn: (r) => r._measurement == "{MEAS}")\n'
            f'  |> filter(fn: (r) => r._field == "value")\n'
            f'  |> filter(fn: (r) => r.source_file == "{used_file}")\n'
            '  |> group(columns: ["_time"])\n'
            '  |> sum(column: "_value")\n'
            '  |> keep(columns: ["_time", "_value"])\n\n'
            'join(tables: {c: c, u: u}, on: ["_time"], method: "inner")\n'
            '  |> map(fn: (r) => ({_time: r._time, _value: r._value_c - r._value_u, metric: "Heap Free"}))\n'
            '  |> group(columns: ["metric"])'
        )
        set_targets(panel, [committed_q, used_q, free_q])
        set_unit(panel, "bytes")


def walk(panels):
    for p in panels:
        tune_panel(p)
        if isinstance(p.get("panels"), list):
            walk(p["panels"])


def panel_exists(panels, title):
    for p in panels:
        if p.get("title") == title:
            return True
        if isinstance(p.get("panels"), list) and panel_exists(p["panels"], title):
            return True
    return False


def max_panel_bottom(panels):
    max_bottom = 0
    for p in panels:
        gp = p.get("gridPos", {})
        y = int(gp.get("y", 0))
        h = int(gp.get("h", 0))
        max_bottom = max(max_bottom, y + h)
        if isinstance(p.get("panels"), list):
            max_bottom = max(max_bottom, max_panel_bottom(p["panels"]))
    return max_bottom


def max_panel_id(panels):
    max_id = 0
    for p in panels:
        max_id = max(max_id, int(p.get("id", 0)))
        if isinstance(p.get("panels"), list):
            max_id = max(max_id, max_panel_id(p["panels"]))
    return max_id


def ensure_response_time_total_panel(doc):
    panels = doc.get("panels", [])
    title = "Response time total"
    preferred_grid = {"h": 8, "w": 12, "x": 0, "y": 33}
    for p in panels:
        if p.get("title") == title:
            p["gridPos"] = preferred_grid
            return

    next_id = max_panel_id(panels) + 1
    panel = {
        "datasource": "InfluxDB-Historical",
        "fieldConfig": {
            "defaults": {
                "color": {"mode": "palette-classic"},
                "custom": {
                    "axisBorderShow": False,
                    "axisCenteredZero": False,
                    "axisColorMode": "text",
                    "axisLabel": "",
                    "axisPlacement": "auto",
                    "barAlignment": 0,
                    "drawStyle": "line",
                    "fillOpacity": 10,
                    "gradientMode": "none",
                    "hideFrom": {"legend": False, "tooltip": False, "vis": False},
                    "lineInterpolation": "linear",
                    "lineWidth": 1,
                    "pointSize": 5,
                    "scaleDistribution": {"type": "linear"},
                    "showPoints": "never",
                    "spanNulls": False,
                    "stacking": {"group": "A", "mode": "none"},
                    "thresholdsStyle": {"mode": "off"},
                },
                "mappings": [],
                "thresholds": {"mode": "absolute", "steps": [{"color": "green"}]},
                "unit": "s",
            },
            "overrides": [],
        },
        "gridPos": preferred_grid,
        "id": next_id,
        "options": {
            "legend": {"calcs": ["mean", "max"], "displayMode": "table", "placement": "bottom", "showLegend": True},
            "tooltip": {"mode": "multi"},
        },
        "pluginVersion": "12.2.0",
        "targets": [
            {
                "refId": "A",
                "query": q_for_file(
                    pick_file(["http_mean_latency.json", "http_p50_latency.json"]),
                    "  |> group()\n"
                    "  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)"
                ),
            },
            {
                "refId": "B",
                "query": q_for_file(
                    pick_file(["http_p95_latency.json"]),
                    "  |> group()\n"
                    "  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)"
                ),
            },
            {
                "refId": "C",
                "query": q_for_file(
                    pick_file(["http_p99_latency.json"]),
                    "  |> group()\n"
                    "  |> aggregateWindow(every: 15s, fn: mean, createEmpty: false)"
                ),
            },
        ],
        "title": title,
        "type": "timeseries",
    }
    panels.append(panel)


def main():
    doc = json.loads(DASH_PATH.read_text(encoding="utf-8"))
    walk(doc.get("panels", []))
    ensure_response_time_total_panel(doc)
    doc["version"] = int(doc.get("version", 1)) + 1
    DASH_PATH.write_text(json.dumps(doc, indent=2), encoding="utf-8")
    print(f"Tuned dashboard: {DASH_PATH}")


if __name__ == "__main__":
    main()
