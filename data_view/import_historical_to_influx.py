#!/usr/bin/env python3
import json
import math
import os
from pathlib import Path
from urllib import error, parse, request


ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = ROOT / "RetriviedData" / "monolith" / "mix" / "1" / "prometheus"
PDMS_EXPORT_DIR = Path(os.getenv("PDMS_EXPORT_DIR", str(DATA_ROOT / "pdms_export_2026-04-27_1450_1516"))).resolve()
PROM_METRICS_DIR = Path(os.getenv("PROM_METRICS_DIR", str(DATA_ROOT / "prometheus_metrics_20260427_153405"))).resolve()

INFLUX_URL = os.getenv("INFLUX_URL", "http://127.0.0.1:8086")
INFLUX_ORG = os.getenv("INFLUX_ORG", "pdms")
INFLUX_BUCKET = os.getenv("INFLUX_BUCKET", "pdms_historical")
INFLUX_TOKEN = os.getenv("INFLUX_TOKEN", "pdms-historical-token")
BATCH_SIZE = int(os.getenv("INFLUX_BATCH_SIZE", "5000"))


def esc_tag(value: str) -> str:
    return value.replace("\\", "\\\\").replace(",", "\\,").replace(" ", "\\ ").replace("=", "\\=")


def esc_measurement(value: str) -> str:
    return value.replace("\\", "\\\\").replace(",", "\\,").replace(" ", "\\ ")


def to_line(measurement: str, tags: dict, value: float, ts_seconds: int) -> str:
    tag_string = ",".join(f"{k}={esc_tag(str(v))}" for k, v in tags.items() if v is not None and str(v) != "")
    if tag_string:
        prefix = f"{esc_measurement(measurement)},{tag_string}"
    else:
        prefix = esc_measurement(measurement)
    # Use seconds precision to match source timestamps.
    return f"{prefix} value={float(value)} {int(ts_seconds)}"


def push_lines(lines):
    if not lines:
        return
    body = ("\n".join(lines)).encode("utf-8")
    params = parse.urlencode(
        {
            "org": INFLUX_ORG,
            "bucket": INFLUX_BUCKET,
            "precision": "s",
        }
    )
    url = f"{INFLUX_URL}/api/v2/write?{params}"
    req = request.Request(url, data=body, method="POST")
    req.add_header("Authorization", f"Token {INFLUX_TOKEN}")
    req.add_header("Content-Type", "text/plain; charset=utf-8")
    try:
        with request.urlopen(req, timeout=30) as resp:
            if resp.status not in (204,):
                raise RuntimeError(f"Influx write failed with status {resp.status}")
    except error.HTTPError as exc:
        details = exc.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"Influx write HTTP {exc.code}: {details}") from exc


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def iter_matrix_points(doc):
    data = doc.get("data", {})
    result = data.get("result", [])
    for series_idx, series in enumerate(result):
        metric = series.get("metric", {})
        values = series.get("values", [])
        for raw_ts, raw_val in values:
            try:
                ts = int(float(raw_ts))
                val = float(raw_val)
            except Exception:
                continue
            if not math.isfinite(val):
                continue
            yield series_idx, metric, ts, val


def import_folder(folder: Path, measurement: str, file_tag_name: str, tag_keys):
    lines = []
    total_points = 0
    files = sorted(folder.glob("*.json"))

    for file in files:
        doc = read_json(file)
        if not isinstance(doc, dict):
            continue

        for series_idx, metric, ts, val in iter_matrix_points(doc):
            tags = {
                file_tag_name: file.name,
                "series_index": series_idx,
            }
            for key in tag_keys:
                tags[key] = metric.get(key)

            lines.append(to_line(measurement, tags, val, ts))
            total_points += 1

            if len(lines) >= BATCH_SIZE:
                push_lines(lines)
                lines = []

    if lines:
        push_lines(lines)

    return len(files), total_points


def main():
    if not PROM_METRICS_DIR.exists():
        raise SystemExit("Prometheus metrics folder was not found. Set PROM_METRICS_DIR env var.")

    print(f"Influx target: {INFLUX_URL} | org={INFLUX_ORG} bucket={INFLUX_BUCKET}")
    pdms_files = 0
    pdms_points = 0
    if PDMS_EXPORT_DIR.exists():
        print("Importing PDMS export files...")
        pdms_files, pdms_points = import_folder(
            folder=PDMS_EXPORT_DIR,
            measurement="pdms_export_metric",
            file_tag_name="source_file",
            tag_keys=["method", "uri", "__name__", "instance", "job"],
        )
        print(f"PDMS done: files={pdms_files}, points={pdms_points}")
    else:
        print(f"Skipping PDMS export import (folder not found): {PDMS_EXPORT_DIR}")

    print("Importing Prometheus metric files...")
    prom_files, prom_points = import_folder(
        folder=PROM_METRICS_DIR,
        measurement="prometheus_metric_file",
        file_tag_name="source_file",
        tag_keys=["__name__", "instance", "job", "method", "uri", "status"],
    )
    print(f"Prometheus done: files={prom_files}, points={prom_points}")
    print(f"Total points imported: {pdms_points + prom_points}")


if __name__ == "__main__":
    main()
