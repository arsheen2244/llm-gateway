#!/usr/bin/env bash
# End-to-end check of a running stack (e.g. after `docker compose up`):
# waits until the gateway answers 200, then sends one normal and one streaming request
# through it to the mock provider and checks both answers. Behind nginx it also checks
# that requests reach more than one gateway instance.
#
# Usage: scripts/smoke-test.sh [gateway-url]     (default http://localhost:8080)
set -euo pipefail

GATEWAY="${1:-http://localhost:8080}"
# Public demo key from the "local" profile; override with GATEWAY_KEY.
KEY="${GATEWAY_KEY:-local-dev-key}"
URL="$GATEWAY/v1/chat/completions"
BODY='{"model":"mock-gpt","messages":[{"role":"user","content":"Say hello"}]}'
STREAM_BODY='{"model":"mock-gpt","stream":true,"messages":[{"role":"user","content":"Say hello"}]}'

post() { curl -s -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" "$@"; }

echo "Waiting for gateway at $GATEWAY ..."
# Wait for a real 200: behind nginx, a 502 only means "no gateway instance is up yet".
status=000
for _ in $(seq 1 90); do
  status=$(post -o /dev/null -w '%{http_code}' -d "$BODY" "$URL" || true)
  [ "$status" = "200" ] && break
  sleep 2
done
[ "$status" = "200" ] || { echo "FAIL: gateway never answered 200 (last status: $status)"; exit 1; }

echo "1) Non-streaming request"
response=$(post -S -f -d "$BODY" "$URL")
echo "$response"
echo "$response" | grep -q '"object":"chat.completion"' || { echo "FAIL: not a chat completion"; exit 1; }

echo "2) Streaming request"
stream=$(post -S -f -N -d "$STREAM_BODY" "$URL")
echo "$stream" | head -5
echo "$stream" | grep -q 'chat.completion.chunk' || { echo "FAIL: no stream chunks"; exit 1; }
echo "$stream" | grep -q 'data: \[DONE\]' || { echo "FAIL: stream did not end with [DONE]"; exit 1; }

echo "3) Load balancing (only behind nginx, which adds X-Upstream)"
upstreams=$(for _ in $(seq 1 9); do
  post -D - -o /dev/null -d "$BODY" "$URL" | tr -d '\r' | awk -F': ' 'tolower($1) == "x-upstream" {print $2}'
done | sort -u)
if [ -z "$upstreams" ]; then
  echo "No X-Upstream header: not behind nginx, skipping."
else
  echo "Instances that answered:"; echo "$upstreams"
  count=$(echo "$upstreams" | wc -l)
  [ "$count" -ge 2 ] || { echo "FAIL: all requests went to one instance"; exit 1; }
fi

echo "Smoke test passed."
