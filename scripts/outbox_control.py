#!/usr/bin/env python3
"""Change the shared PDMS outbox policy over HTTP, without restarting services."""
import argparse
import json
import os
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


class NoRedirect(HTTPRedirectHandler):
    """Never forward the admin secret to a redirected host."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="Direct backend URL, for example http://localhost:8080")
    parser.add_argument("--gateway", action="store_true", help="Use gateway /admin/outbox and PDMS_GATEWAY_ADMIN_TOKEN")
    parser.add_argument("--expected-version", type=int, help="Otherwise read the current version before applying")
    parser.add_argument("--request-id", help="UUID for replaying exactly the same request")
    parser.add_argument("--reason", default="Operator outbox configuration")
    parser.add_argument("--timeout", type=float, default=60, help="HTTP timeout in seconds")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status")
    ledger = commands.add_parser("ledger", help="Ledger status or independent on/off control")
    ledger.add_argument("state", nargs="?", choices=["status", "on", "off"], default="status")
    commands.add_parser("pause")
    activate = commands.add_parser("activate")
    activate.add_argument("architecture", choices=["monolith", "microservices", "microservices-async", "faas", "decentralized"])
    types = commands.add_parser("types")
    types.add_argument("architecture", choices=["monolith", "microservices", "microservices-async", "faas", "decentralized"])
    types.add_argument("event_types", nargs="*", help="Exact types; omit to disable all types for this architecture")
    onchain = commands.add_parser("onchain")
    onchain.add_argument("state", choices=["on", "off"])
    ordinary = commands.add_parser("decentralized-ordinary", help="Allow decentralized to process ordinary events when selected")
    ordinary.add_argument("state", choices=["on", "off"])
    onchain_types = commands.add_parser("onchain-types")
    onchain_types.add_argument("anchor_types", nargs="*")
    args = parser.parse_args(argv)
    parsed = urlsplit(args.url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password:
        parser.error("--url must be an HTTP(S) service URL without embedded credentials")
    token_variable = "PDMS_GATEWAY_ADMIN_TOKEN" if args.gateway else "PDMS_OUTBOX_ADMIN_TOKEN"
    token = os.environ.get(token_variable, "")
    if not token:
        parser.error(f"Set {token_variable} in the environment")
    if args.command == "ledger" and args.state == "status" and not args.gateway:
        parser.error("ledger requires --gateway so the decentralized backend can be selected explicitly")
    url = args.url.rstrip("/") + ("/admin/outbox" if args.gateway else "/actuator/outbox-control")
    opener = build_opener(NoRedirect())

    def call(method, body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        headers["Authorization" if args.gateway else "X-Outbox-Admin-Token"] = "Bearer " + token if args.gateway else token
        request = Request(url, data=data, method=method, headers=headers)
        with opener.open(request, timeout=args.timeout) as response:
            return json.load(response)

    try:
        if args.command == "ledger" and args.state == "status":
            url += "/ledger"
            result = call("GET")
        elif args.command == "status":
            result = call("GET")
        else:
            version = args.expected_version
            if version is None:
                version = call("GET")["snapshot"]["version"]
            body = {"expectedVersion": version, "requestId": args.request_id or str(uuid.uuid4()), "reason": args.reason}
            if args.command == "pause":
                body["enabled"] = False
            elif args.command == "activate":
                body.update(enabled=True, activeArchitecture=args.architecture)
            elif args.command == "types":
                body["allowedEventTypes"] = {args.architecture: args.event_types}
            elif args.command == "onchain":
                body["onchainProcessingEnabled"] = args.state == "on"
            elif args.command == "ledger":
                body["ledgerProcessingEnabled"] = args.state == "on"
                if args.state == "on":
                    body["onchainProcessingEnabled"] = True
            elif args.command == "decentralized-ordinary":
                body["decentralizedOrdinaryProcessingEnabled"] = args.state == "on"
            else:
                body["allowedOnchainAnchorTypes"] = args.anchor_types
            result = call("PATCH", body)
        print(json.dumps(result, indent=2))
        return 0
    except HTTPError as error:
        print(f"HTTP {error.code}: {error.read().decode('utf-8', errors='replace')}", file=sys.stderr)
    except (URLError, TimeoutError, OSError, ValueError, KeyError) as error:
        print(f"Outbox control failed: {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
