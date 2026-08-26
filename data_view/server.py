#!/usr/bin/env python3
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse


BASE_DIR = Path(__file__).resolve().parent
DATA_ROOT = (BASE_DIR.parent / "RetriviedData" / "monolith" / "mix" / "1" / "prometheus").resolve()
PDMS_EXPORT_DIR = DATA_ROOT / "pdms_export_2026-04-27_1450_1516"
PROM_METRICS_DIR = DATA_ROOT / "prometheus_metrics_monolith_20260427_215815"
HOST = "127.0.0.1"
PORT = 8099


QUERY_FILE_MAP = [
    ("system_cpu_usage", "metrics_cpu.json"),
    ("jvm_memory_used_bytes{area=\"heap\"}", "metrics_heap_used.json"),
    ("jvm_memory_max_bytes{area=\"heap\"}", "metrics_heap_max.json"),
    ("jvm_memory_committed_bytes{area=\"heap\"}", "metrics_heap_committed.json"),
    ("jvm_memory_used_bytes{area=\"nonheap\"}", "metrics_nonheap.json"),
    ("jvm_threads_live_threads", "metrics_threads_active.json"),
    ("jvm_threads_daemon_threads", "metrics_threads_daemon.json"),
    ("jvm_threads_peak_threads", "metrics_threads_peak.json"),
    ("jvm_gc_pause_seconds_count", "metrics_gc_count.json"),
    ("jvm_gc_pause_seconds_sum", "metrics_gc_pause_time.json"),
    ("jvm_classes_loaded_classes", "metrics_classes_loaded.json"),
    ("process_resident_memory_bytes", "metrics_process_resident_memory.json"),
    ("process_virtual_memory_bytes", "metrics_process_virtual_memory.json"),
    ("process_cpu_seconds_total", "metrics_process_cpu_seconds.json"),
    ("hikaricp_connections_active", "metrics_db_connections_active.json"),
    ("hikaricp_connections_idle", "metrics_db_connections_idle.json"),
    ("hikaricp_connections_pending", "metrics_db_connections_pending.json"),
    ("hikaricp_connections_timeout_total", "metrics_db_connections_timeout.json"),
    ("http_server_requests_active_seconds_count", "metrics_request_count.json"),
    ("http_server_requests_seconds_max", "metrics_p999_latency.json"),
    ("histogram_quantile(0.99", "metrics_p99_latency.json"),
    ("histogram_quantile(0.95", "metrics_p95_latency.json"),
    ("histogram_quantile(0.50", "metrics_p50_latency.json"),
    ("http_server_request_bytes_sum", "metrics_http_total_requests_counter.json"),
    ("http_server_response_bytes_sum", "metrics_http_total_requests_dashboard_style.json"),
    ("status=~\"5..\"", "metrics_http_5xx_percentage.json"),
    ("status=~\"4..\"", "metrics_http_4xx_percentage.json"),
    ("status=~\"2..\"", "metrics_http_success_percentage.json"),
    ("http_server_requests_seconds_count", "metrics_http_total_rps.json"),
    ("pdms_data_export_request_total", "metrics_rps.json"),
    ("pdms_data_deletion_request_total", "metrics_client_error_rate.json"),
    ("pdms_dsar_requests_in_progress", "metrics_request_count.json"),
    ("pdms_data_deletion_requests_in_progress", "metrics_client_error_rate.json"),
    ("spring_kafka_producer_record_send_total", "metrics_kafka_producer_rate.json"),
    ("spring_kafka_consumer_record_fetch_total", "metrics_kafka_consumer_rate.json"),
    ("spring_kafka_consumer_lag_sum", "metrics_kafka_consumer_lag.json"),
    ("spring_kafka_template_seconds", "metrics_kafka_producer_rate.json")
]


def read_file_bytes(path: Path):
    if not str(path.resolve()).startswith(str(DATA_ROOT)) or not path.exists() or not path.is_file():
        return None
    try:
        return path.read_bytes()
    except OSError:
        return None


def map_query_to_file(query: str):
    q = query or ""
    for needle, filename in QUERY_FILE_MAP:
        if needle in q:
            return PROM_METRICS_DIR / filename
    return None


class Handler(SimpleHTTPRequestHandler):
    def _send_json(self, payload, status=200):
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _handle_query_range(self, query: str):
        mapped = map_query_to_file(query)
        if mapped is None:
            self._send_json({"status": "success", "data": {"resultType": "matrix", "result": []}})
            return

        data = read_file_bytes(mapped)
        if data is None:
            self.send_error(404, "Not found")
            return

        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        path_only = parsed.path

        if path_only == "/":
            self._send_json({
                "status": "ok",
                "message": "PDMS adapter is running",
                "endpoints": ["/api/v1/query_range", "/data/<file>"]
            })
            return

        if path_only == "/api/v1/query_range":
            query = parse_qs(parsed.query).get("query", [""])[0]
            self._handle_query_range(query)
            return

        if path_only.startswith("/data/"):
            rel = unquote(path_only[len("/data/"):]).lstrip("/")
            target = (DATA_ROOT / rel).resolve()

            # Block path traversal and serve data as read-only bytes.
            data = read_file_bytes(target)
            if data is None:
                self.send_error(404, "Not found")
                return

            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        super().do_GET()

    def do_POST(self):
        parsed = urlparse(self.path)
        path_only = parsed.path
        if path_only != "/api/v1/query_range":
            self.send_error(404, "Not found")
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        body_raw = self.rfile.read(content_length).decode("utf-8", errors="ignore")
        query = parse_qs(body_raw).get("query", [""])[0]
        self._handle_query_range(query)


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Serving PDMS Grafana adapter on http://{HOST}:{PORT}")
    print("Press Ctrl+C to stop.")
    server.serve_forever()
