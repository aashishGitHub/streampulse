#!/usr/bin/env python3
"""End-to-end event accounting against the running stack (make up-core, make run-all).

Sends N events with known ids, times and sessions into ONE aggregate key (a unique region), re-sends
some batches exactly as a retrying client would, optionally runs shell commands at chosen batch
numbers (to kill and restart a service mid-run), advances stream time with a closing event, then
compares EVERY closed window in Postgres with what was sent.

It answers: did any event go missing, or get counted twice, anywhere between HTTP and the store?
It does not prove anything about Redis or the SSE feed.

Needs LOADGEN_CLIENT_SECRET (the ingest-gateway client secret from the local Keycloak realm file).
Standard library only.
"""

import argparse
import json
import os
import random
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

WINDOW_MS = 10_000
GRACE_MS = 5_000


def now_ms() -> int:
    return int(time.time() * 1000)


def iso(ms: int) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(ms // 1000)) + ".%03dZ" % (ms % 1000)


def window_of(ms: int) -> int:
    return ms // WINDOW_MS * WINDOW_MS


def fetch_token(keycloak: str, secret: str) -> str:
    body = urllib.parse.urlencode(
        {"grant_type": "client_credentials", "client_id": "ingest-gateway", "client_secret": secret}
    ).encode()
    url = keycloak.rstrip("/") + "/realms/streampulse/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=body), timeout=10) as resp:
        return json.load(resp)["access_token"]


def post(url: str, token: str, payload: bytes):
    request = urllib.request.Request(
        url.rstrip("/") + "/v1/events:batch",
        data=payload,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def send_until_accepted(url: str, token: str, payload: bytes, patience_s: int = 90):
    """A client that retries the SAME bytes until it gets a 202, as a real one does."""
    deadline = time.time() + patience_s
    attempts = 0
    while True:
        attempts += 1
        try:
            status, body = post(url, token, payload)
            if status == 202:
                return attempts, body
        except OSError:
            pass  # connection refused or reset: the service is down; retry
        if time.time() > deadline:
            raise SystemExit("batch never accepted within %ds" % patience_s)
        time.sleep(1)


def psql(container: str, sql: str):
    out = subprocess.check_output(
        ["docker", "exec", container, "psql", "-U", "streampulse", "-d", "streampulse", "-t", "-A", "-F", "|", "-c", sql],
        text=True,
    )
    return [line.split("|") for line in out.strip().splitlines() if line]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--events", type=int, default=2000)
    ap.add_argument("--batch", type=int, default=100)
    ap.add_argument("--interval", type=float, default=1.0, help="seconds between batches")
    ap.add_argument("--dup-every", type=int, default=5, help="re-send every Nth batch unchanged (client retry); 0 = never")
    ap.add_argument("--at", action="append", default=[], metavar="BATCH:CMD",
                    help="run shell command CMD just before sending batch number BATCH (1-based); repeatable")
    ap.add_argument("--ingest", default="http://localhost:8080")
    ap.add_argument("--keycloak", default="http://localhost:8081")
    ap.add_argument("--pg-container", default="sp-postgres")
    ap.add_argument("--wait", type=int, default=120, help="seconds to wait for windows to appear in Postgres")
    args = ap.parse_args()

    secret = os.environ.get("LOADGEN_CLIENT_SECRET")
    if not secret:
        print("LOADGEN_CLIENT_SECRET is not set (see tools/loadgen/README.md)", file=sys.stderr)
        return 2
    hooks = {}
    for spec in args.at:
        n, _, cmd = spec.partition(":")
        hooks.setdefault(int(n), []).append(cmd)

    token = fetch_token(args.keycloak, secret)
    run_id = uuid.uuid4().hex[:8]
    region = "acct-" + run_id
    rng = random.Random(1)
    # window start (ms) -> {"events": n, "errors": n, "sessions": set}
    expected: dict[int, dict] = {}
    sent = 0
    batches = (args.events + args.batch - 1) // args.batch
    duplicates_sent = 0
    retries = 0
    last_event_ms = 0

    print("run %s: %d events in %d batches, region=%s" % (run_id, args.events, batches, region))
    for b in range(1, batches + 1):
        for cmd in hooks.get(b, []):
            print("  [batch %d] running: %s" % (b, cmd))
            subprocess.run(cmd, shell=True, check=False)
        ts = now_ms()
        events = []
        for _ in range(min(args.batch, args.events - sent)):
            is_error = (sent % 10) == 0
            session = "acct-s-%d" % rng.randrange(100)
            events.append({
                "eventId": str(uuid.uuid4()), "sessionId": session, "titleId": "acct",
                "deviceType": "WEB", "region": region, "occurredAt": iso(ts),
                "type": "ERROR" if is_error else "HEARTBEAT",
            })
            w = expected.setdefault(window_of(ts), {"events": 0, "errors": 0, "sessions": set()})
            w["events"] += 1
            w["errors"] += 1 if is_error else 0
            w["sessions"].add(session)
            sent += 1
        last_event_ms = ts
        payload = json.dumps({"events": events}).encode()
        attempts, body = send_until_accepted(args.ingest, token, payload)
        retries += attempts - 1
        if args.dup_every and b % args.dup_every == 0:
            send_until_accepted(args.ingest, token, payload)  # the client retries after a lost response
            duplicates_sent += len(events)
        time.sleep(args.interval)

    # Stream time only advances with later events: wait past the last window's end + grace, then send
    # one closing event into the SAME aggregate key (same title and region, so the same partition).
    close_at = window_of(last_event_ms) + WINDOW_MS + GRACE_MS + 3_000
    time.sleep(max(0, (close_at - now_ms()) / 1000))
    tick_ms = now_ms()
    tick = {"events": [{
        "eventId": str(uuid.uuid4()), "sessionId": "acct-tick", "titleId": "acct", "deviceType": "WEB",
        "region": region, "occurredAt": iso(tick_ms), "type": "HEARTBEAT"}]}
    send_until_accepted(args.ingest, token, json.dumps(tick).encode())
    tick_window = window_of(tick_ms)
    closed = sorted(w for w in expected if w + WINDOW_MS + GRACE_MS <= tick_ms)
    print("sent %d unique events (+%d re-sent as retries, +%d connection retries); closing event at window %s"
          % (sent, duplicates_sent, retries, iso(tick_window)))

    deadline = time.time() + args.wait
    rows = {}
    while time.time() < deadline:
        found = psql(args.pg_container,
                     "select (extract(epoch from window_start)*1000)::bigint, metric, value, sample_count "
                     "from metric_window where region='%s'" % region)
        rows = {(int(r[0]), r[1]): (float(r[2]), int(r[3])) for r in found}
        if all((w, m) in rows for w in closed for m in ("CONCURRENT_VIEWERS", "ERROR_RATE")):
            break
        time.sleep(2)

    failures = []
    print("\n%-26s %8s %8s %10s %10s %8s" % ("window", "sent", "stored", "viewers", "err_rate", "verdict"))
    total_sent = total_stored = 0
    for w in closed:
        exp = expected[w]
        viewers = rows.get((w, "CONCURRENT_VIEWERS"))
        errors = rows.get((w, "ERROR_RATE"))
        stored = errors[1] if errors else 0
        ok = (viewers is not None and errors is not None
              and errors[1] == exp["events"] and viewers[1] == exp["events"]
              and int(viewers[0]) == len(exp["sessions"])
              and abs(errors[0] - exp["errors"] / exp["events"]) < 1e-9)
        total_sent += exp["events"]
        total_stored += stored
        print("%-26s %8d %8d %10s %10s %8s" % (
            iso(w), exp["events"], stored,
            "%d/%d" % (int(viewers[0]), len(exp["sessions"])) if viewers else "MISSING",
            "%.4f" % errors[0] if errors else "MISSING", "ok" if ok else "FAIL"))
        if not ok:
            failures.append(w)

    unexpected = sorted({w for (w, _m) in rows} - set(expected) - {tick_window})
    for w in unexpected:
        print("unexpected window in Postgres: %s" % iso(w))
        failures.append(w)
    print("\nclosed windows checked: %d   events sent: %d   events stored: %d" % (len(closed), total_sent, total_stored))
    print("RESULT: %s" % ("PASS" if not failures else "FAIL (%d window(s))" % len(failures)))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
