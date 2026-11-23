#!/usr/bin/env python3
"""
Traffic generator for testing BurpSpecDrift hit counting.

It starts a tiny local web server and fires several hundred HTTP requests at it
*through the Burp proxy*, so they land in Burp's proxy history and are counted by
the extension. Requests are split into:
  - "matching" requests, each tagged with the spec route it should be counted against;
  - "noise" requests that must match no route (edge cases: wrong method, wrong host,
    trailing slash, wrong case, missing base-path, wrong extension, extra segment).

At the end it prints the EXPECTED proxy-hit count per spec route. Compare that with
the actual counts from Burp via the extension's "Copy Hits to Clipboard" menu item.

Pair this with examples/coverage-test.yaml and docs/testing-hit-counting.md.

Usage:
    python3 scripts/hit_counting_test.py                 # server on :8000, proxy 127.0.0.1:8080
    python3 scripts/hit_counting_test.py --proxy 127.0.0.1:8080 --server-port 8000
    python3 scripts/hit_counting_test.py --no-server     # if you already run a server on :8000

Needs only the Python 3 standard library.
"""

import argparse
import random
import sys
import threading
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import URLError
from urllib.request import ProxyHandler, Request, build_opener


# ---------------------------------------------------------------------------- server

class _Handler(BaseHTTPRequestHandler):
    def _ok(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    # Respond 200 to everything, so every proxied request becomes a clean history entry.
    do_GET = _ok
    do_POST = _ok
    do_DELETE = _ok
    do_PUT = _ok

    def log_message(self, *_args):
        pass  # keep the console clean


def start_server(port):
    server = ThreadingHTTPServer(("127.0.0.1", port), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


# ---------------------------------------------------------------------------- plan

def build_plan(server_port):
    """Returns (requests, expected) where requests is a list of (method, url, key|None)."""
    base = f"http://localhost:{server_port}/api/v1"          # root server in the spec
    legacy = f"http://127.0.0.1:{server_port}"               # operation-level override host
    other_host = f"http://127.0.0.1:{server_port}/api/v1"    # right path, wrong host
    no_base = f"http://localhost:{server_port}"              # missing /api/v1 base path

    requests = []

    def add(count, method, url_fn, key):
        for i in range(count):
            requests.append((method, url_fn(i), key))

    # --- matching requests (key = "METHOD\t<spec path>") ---
    add(40, "GET", lambda i: f"{base}/users", "GET\t/users")
    add(30, "POST", lambda i: f"{base}/users", "POST\t/users")
    add(60, "GET", lambda i: f"{base}/users/{i + 1}", "GET\t/users/{id}")
    add(20, "DELETE", lambda i: f"{base}/users/{i + 1}", "DELETE\t/users/{id}")
    add(25, "GET", lambda i: f"{base}/users/me", "GET\t/users/me")
    add(35, "GET", lambda i: f"{base}/users/{i + 1}/posts/{i + 100}", "GET\t/users/{id}/posts/{postId}")
    add(28, "GET", lambda i: f"{base}/files/report{i}.json", "GET\t/files/{name}.json")
    add(50, "GET", lambda i: f"{base}/health", "GET\t/health")
    add(32, "GET", lambda i: f"{base}/search?q=foo&page={i}", "GET\t/search")
    add(22, "GET", lambda i: f"{legacy}/legacy", "GET\t/legacy")

    # --- noise: must match nothing (key = None) ---
    add(15, "GET", lambda i: f"{base}/users/5/extra", None)       # extra segment past {id}
    add(12, "GET", lambda i: f"{base}/files/report.txt", None)    # wrong extension
    add(10, "POST", lambda i: f"{base}/users/5", None)            # method not defined on /users/{id}
    add(8, "GET", lambda i: f"{base}/health/", None)              # trailing slash
    add(8, "GET", lambda i: f"{base}/Users/5", None)              # wrong case
    add(14, "GET", lambda i: f"{base}/nonexistent", None)         # unknown path
    add(10, "GET", lambda i: f"{no_base}/legacy", None)           # legacy path, wrong host (localhost)
    add(10, "GET", lambda i: f"{other_host}/users/5", None)       # users path, wrong host (127.0.0.1)
    add(8, "GET", lambda i: f"{no_base}/users/5", None)           # missing /api/v1 base path

    expected = Counter(key for (_m, _u, key) in requests if key is not None)
    return requests, expected


# ---------------------------------------------------------------------------- run

def fire(requests, proxy):
    opener = build_opener(ProxyHandler({"http": f"http://{proxy}"}))
    sent, failed = 0, 0
    for method, url, _key in requests:
        data = b"{}" if method in ("POST", "PUT") else None
        req = Request(url, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with opener.open(req, timeout=10) as resp:
                resp.read()
            sent += 1
        except URLError as e:
            failed += 1
            if failed <= 3:
                print(f"  ! request failed ({method} {url}): {e}", file=sys.stderr)
    return sent, failed


def main():
    parser = argparse.ArgumentParser(description="BurpSpecDrift hit-counting traffic generator")
    parser.add_argument("--proxy", default="127.0.0.1:8080", help="Burp proxy host:port")
    parser.add_argument("--server-port", type=int, default=8000, help="local web server port")
    parser.add_argument("--no-server", action="store_true", help="do not start the local web server")
    parser.add_argument("--seed", type=int, default=1, help="shuffle seed (for reproducibility)")
    args = parser.parse_args()

    server = None
    if not args.no_server:
        server = start_server(args.server_port)
        print(f"Local web server on http://127.0.0.1:{args.server_port}")

    requests, expected = build_plan(args.server_port)
    random.Random(args.seed).shuffle(requests)

    print(f"Sending {len(requests)} requests via proxy {args.proxy} ...")
    sent, failed = fire(requests, args.proxy)
    print(f"Done: {sent} sent, {failed} failed.\n")

    if failed:
        print("WARNING: some requests failed — is the Burp proxy listening and intercept off?\n")

    noise = sum(1 for (_m, _u, k) in requests if k is None)
    print("EXPECTED proxy hits per spec route (method <TAB> path <TAB> count):")
    for key in sorted(expected):
        print(f"  {key}\t{expected[key]}")
    print(f"\nNoise requests (should add 0 hits anywhere): {noise}")
    print(f"Total matching requests: {sum(expected.values())}\n")
    print("Now in Burp: SpecDrift menu -> 'Copy Hits to Clipboard', paste, and compare the")
    print("proxy column against the counts above. See docs/testing-hit-counting.md.")

    if server is not None:
        server.shutdown()


if __name__ == "__main__":
    main()
