#!/usr/bin/env bash
# Checks usage accounting end to end on a running Compose stack: sends a known number of
# requests through nginx (so through all gateway instances) and waits until PostgreSQL holds
# exactly that many events, priced, with no event counted twice or missing from the totals.
#
# Usage: scripts/check-usage.sh [gateway-url]     (default http://localhost:8080)
set -euo pipefail

GATEWAY="${1:-http://localhost:8080}"
KEY="${GATEWAY_KEY:-local-dev-key}"
N=30
# A model name nothing else uses (the mock accepts any mock-* name), to pick out our events.
MODEL="mock-usage-check-$(date +%s)-$RANDOM"

sql() { docker compose exec -T postgres psql -U usage -d usage -At -v ON_ERROR_STOP=1 -c "$1"; }

echo "Waiting for the consumer to create the schema ..."
ready=""
for _ in $(seq 1 60); do
  ready=$(sql "SELECT to_regclass('usage_daily')" 2>/dev/null || true)
  [ "$ready" = "usage_daily" ] && break
  sleep 2
done
[ "$ready" = "usage_daily" ] || { echo "FAIL: no usage schema"; docker compose logs usage-consumer | tail -50; exit 1; }

# A price for the model, before any request uses it: $1 in, $2 out per million tokens.
sql "INSERT INTO model_prices VALUES ('mock-openai', '$MODEL', '2020-01-01', 1, 2)" > /dev/null

echo "Sending $N requests (half of them streams) as $MODEL"
for i in $(seq 1 $N); do
  stream=$([ $((i % 2)) = 0 ] && echo true || echo false)
  curl -s -S -f -o /dev/null -X POST -H 'Content-Type: application/json' -H "Authorization: Bearer $KEY" \
    -d "{\"model\":\"$MODEL\",\"stream\":$stream,\"messages\":[{\"role\":\"user\",\"content\":\"Request $i\"}]}" \
    "$GATEWAY/v1/chat/completions"
done

count=0
for _ in $(seq 1 60); do
  count=$(sql "SELECT count(*) FROM usage_events WHERE model = '$MODEL'")
  [ "$count" -ge "$N" ] && break
  sleep 1
done
sleep 2 # would any extra event still arrive?
count=$(sql "SELECT count(*) FROM usage_events WHERE model = '$MODEL'")
echo "Events in PostgreSQL: $count of $N"
[ "$count" = "$N" ] || { echo "FAIL: expected exactly $N"; docker compose logs usage-consumer | tail -50; exit 1; }

bad=$(sql "SELECT count(*) FROM usage_events WHERE model = '$MODEL' AND (status <> 'SUCCESS'
  OR provider IS DISTINCT FROM 'mock-openai' OR prompt_tokens = 0 OR completion_tokens = 0
  OR cost_usd IS DISTINCT FROM (prompt_tokens + 2 * completion_tokens) / 1000000.0)")
[ "$bad" = "0" ] || { echo "FAIL: $bad events with wrong status, provider, tokens or cost"; exit 1; }

# The daily totals must equal the sum of the events they were built from, for every client.
wrong=$(sql "SELECT count(*) FROM
  (SELECT client_id, (occurred_at AT TIME ZONE 'UTC')::date AS day, count(*) AS n, coalesce(sum(cost_usd), 0) AS cost
     FROM usage_events GROUP BY 1, 2) e
  FULL JOIN usage_daily d USING (client_id, day)
  WHERE e.n IS DISTINCT FROM d.requests OR e.cost IS DISTINCT FROM d.cost_usd")
[ "$wrong" = "0" ] || { echo "FAIL: daily totals differ from the events"; sql "SELECT * FROM usage_daily"; exit 1; }

sql "SELECT client_id, day, requests, prompt_tokens, completion_tokens, cost_usd FROM usage_daily"
echo "Usage check passed."
