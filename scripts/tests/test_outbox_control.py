"""CLI checks use a disposable HTTP server; no application database or admin secret."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("outbox_control", Path(__file__).resolve().parents[1] / "outbox_control.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class OutboxCliTest(unittest.TestCase):
    def setUp(self):
        self.requests = []
        self.authorizations = []
        recorded = self.requests
        authorizations = self.authorizations

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                authorizations.append(self.headers.get("Authorization"))
                recorded.append(("GET", self.path, self.headers.get("X-Outbox-Admin-Token"), None))
                self.answer({"snapshot": {"version": 7}})

            def do_PATCH(self):
                authorizations.append(self.headers.get("Authorization"))
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                recorded.append(("PATCH", self.path, self.headers.get("X-Outbox-Admin-Token"), body))
                if body.get("expectedVersion") != 7:
                    self.answer({"error": "Stale expectedVersion"}, 409)
                else:
                    self.answer({"version": 8})

            def answer(self, body, status=200):
                payload = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.output = io.StringIO()
        self.errors = io.StringIO()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def run_cli(self, *args):
        with patch.dict(os.environ, {"PDMS_OUTBOX_ADMIN_TOKEN": "test-only", "PDMS_GATEWAY_ADMIN_TOKEN": "gateway-only"}), \
                contextlib.redirect_stdout(self.output), contextlib.redirect_stderr(self.errors):
            return cli.main(["--url", self.url, *args])

    def test_activate_reads_version_and_submits_single_change(self):
        self.assertEqual(self.run_cli("activate", "microservices-async"), 0)
        self.assertEqual([r[0] for r in self.requests], ["GET", "PATCH"])
        method, path, token, body = self.requests[-1]
        self.assertEqual(path, "/actuator/outbox-control")
        self.assertEqual(token, "test-only")
        self.assertEqual(body["expectedVersion"], 7)
        self.assertEqual(body["activeArchitecture"], "microservices-async")
        self.assertTrue(body["enabled"])
        self.assertNotIn("test-only", self.output.getvalue())

    def test_empty_types_is_explicit_empty_allowlist(self):
        self.assertEqual(self.run_cli("types", "decentralized"), 0)
        self.assertEqual(self.requests[-1][3]["allowedEventTypes"], {"decentralized": []})

    def test_stale_version_is_not_automatically_retried(self):
        self.assertEqual(self.run_cli("--expected-version", "1", "pause"), 1)
        self.assertEqual(len(self.requests), 1)
        self.assertIn("409", self.errors.getvalue())

    def test_onchain_flag_does_not_implicitly_activate_owner(self):
        self.assertEqual(self.run_cli("onchain", "on"), 0)
        self.assertTrue(self.requests[-1][3]["onchainProcessingEnabled"])
        self.assertNotIn("activeArchitecture", self.requests[-1][3])

    def test_gateway_mode_uses_gateway_bearer_and_endpoint(self):
        self.assertEqual(self.run_cli("--gateway", "activate", "decentralized"), 0)
        self.assertEqual([row[1] for row in self.requests], ["/admin/outbox", "/admin/outbox"])
        self.assertTrue(all(row[2] is None for row in self.requests))
        self.assertEqual(self.authorizations, ["Bearer gateway-only", "Bearer gateway-only"])
        self.assertNotIn("gateway-only", self.output.getvalue())

    def test_gateway_ledger_queries_decentralized_status(self):
        self.assertEqual(self.run_cli("--gateway", "ledger"), 0)
        self.assertEqual(self.requests[0][1], "/admin/outbox/ledger")

    def test_ledger_on_does_not_activate_ordinary_processing_or_change_owner(self):
        self.assertEqual(self.run_cli("--gateway", "ledger", "on"), 0)
        body = self.requests[-1][3]
        self.assertTrue(body["ledgerProcessingEnabled"])
        self.assertTrue(body["onchainProcessingEnabled"])
        self.assertNotIn("enabled", body)
        self.assertNotIn("activeArchitecture", body)

    def test_decentralized_ordinary_flag_is_explicit(self):
        self.assertEqual(self.run_cli("--gateway", "decentralized-ordinary", "on"), 0)
        body = self.requests[-1][3]
        self.assertTrue(body["decentralizedOrdinaryProcessingEnabled"])
        self.assertNotIn("ledgerProcessingEnabled", body)

    def test_pause_does_not_change_ledger_flags(self):
        self.assertEqual(self.run_cli("--gateway", "pause"), 0)
        self.assertFalse(self.requests[-1][3]["enabled"])
        self.assertNotIn("ledgerProcessingEnabled", self.requests[-1][3])
        self.assertNotIn("onchainProcessingEnabled", self.requests[-1][3])


if __name__ == "__main__":
    unittest.main()
