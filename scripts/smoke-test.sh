#!/usr/bin/env bash
# End-to-end check of a running stack (e.g. after `docker compose up`):
# waits for the gateway, then sends one normal and one streaming request
# through it to the mock provider and checks both answers.
#
# Usage: scripts/smoke-test.sh [gateway-url]     (default http://localhost:8080)
set -euo pipefail

GATEWAY="${1:-http://localhost:8080}"
# Public demo key from the "local" profile; override with GATEWAY_KEY.
KEY="${GATEWAY_KEY:-local-dev-key}"
URL="$GATEWAY/v1/chat/completions"
BODY='{"model":"mock-gpt","messages":[{"role":"user","content":"Say hello"}]}'
STREAM_BODY='{"model":"mock-gpt","stream":true,"messages":[{"role":"user","content":"Say hello"}]}'

echo "Waiting for gateway at $GATEWAY ..."
for _ in $(seq 1 60); do
  # Any HTTP answer (even an error status) means the server is up.
  if curl -s -o /dev/null -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" -d "$BODY" "$URL"; then
    break
  fi
  sleep 2
done

echo "1) Non-streaming request"
response=$(curl -sS -f -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" -d "$BODY" "$URL")
echo "$response"
echo "$response" | grep -q '"object":"chat.completion"' || { echo "FAIL: not a chat completion"; exit 1; }

echo "2) Streaming request"
stream=$(curl -sS -f -N -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" -d "$STREAM_BODY" "$URL")
echo "$stream" | head -5
echo "$stream" | grep -q 'chat.completion.chunk' || { echo "FAIL: no stream chunks"; exit 1; }
echo "$stream" | grep -q 'data: \[DONE\]' || { echo "FAIL: stream did not end with [DONE]"; exit 1; }

echo "Smoke test passed."
