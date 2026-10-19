#!/usr/bin/env bash
# Proves each proxy carries real traffic AND that disabling it breaks that traffic.
# A proxy clients silently bypass would make every fault-injection test pass for nothing.
#
# Clients run in toxiproxy's network namespace, so localhost:<port> is the proxy, the same
# address host-run services use.
set -euo pipefail

API=http://localhost:8474
NS=container:sp-toxiproxy
KAFKA_IMG=apache/kafka:3.9.1
PG_IMG=postgres:16-alpine
REDIS_IMG=redis:7-alpine

fail() { echo "FAIL: $*" >&2; exit 1; }

kafka_ok() {
  # Short admin timeouts, so a dead proxy fails in seconds instead of the 60 s default.
  docker run --rm --network "$NS" "$KAFKA_IMG" sh -c '
    printf "request.timeout.ms=3000\ndefault.api.timeout.ms=5000\n" > /tmp/c.properties
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 \
      --command-config /tmp/c.properties --list' >/dev/null 2>&1
}
pg_ok()    { docker run --rm --network "$NS" "$PG_IMG" pg_isready -h localhost -p 25432 -t 3 >/dev/null 2>&1; }
redis_ok() { [ "$(docker run --rm --network "$NS" "$REDIS_IMG" redis-cli -p 26379 ping 2>/dev/null)" = "PONG" ]; }

set_enabled() {
  curl -sf -X POST "$API/proxies/$1" -H 'Content-Type: application/json' \
    -d "{\"enabled\": $2}" >/dev/null || fail "could not set $1 enabled=$2"
}

echo "== toxiproxy API"
proxies=$(curl -sf "$API/proxies") || fail "toxiproxy API not reachable on :8474"
for p in kafka postgres redis; do
  echo "$proxies" | grep -q "\"$p\"" || fail "proxy '$p' not defined"
done
echo "   kafka, postgres, redis defined"

# Disabling the proxy only proves a NEW client is cut off. A long-lived client that bootstrapped
# through the proxy then talks to the broker's advertised address, so that address must be the proxy.
echo "== kafka advertises the proxy to clients that bootstrap through it"
advertised=$(docker run --rm --network "$NS" "$KAFKA_IMG" \
  /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:29092 2>/dev/null | grep ' (id: ' || true)
echo "$advertised" | grep -q '^localhost:29092 (id: ' \
  || fail "broker advertises '${advertised%% (id:*}', not localhost:29092: clients would bypass the proxy"
echo "   advertised: localhost:29092"

for p in kafka postgres redis; do
  check="${p/postgres/pg}_ok"
  echo "== $p through the proxy"
  "$check" || fail "$p not reachable through its proxy"
  echo "   reachable"
  set_enabled "$p" false
  if "$check"; then set_enabled "$p" true; fail "$p still reachable with its proxy disabled: traffic bypasses the proxy"; fi
  echo "   unreachable once disabled"
  set_enabled "$p" true
  "$check" || fail "$p did not recover after re-enabling its proxy"
  echo "   recovered once re-enabled"
done

echo "PASS: all three proxies carry traffic and their faults bite"
