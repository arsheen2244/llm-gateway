#!/usr/bin/env bash
# The full-stack measurement (design note 43). For each rate: 60 s straight to the mock, then
# 60 s through nginx and the gateways, then how long the usage consumer takes to record every
# request that reached a gateway. Results go to $OUT_DIR (default load-test/results/full-stack).
#
# Usage (from the repo root, Compose stack up, k6 installed):
#   bash load-test/full_stack.sh [rate ...]        default rates: 100 200 400 600 800 1000 1200 1600 2000
set -euo pipefail

RATES=("${@:-100 200 400 600 800 1000 1200 1600 2000}")
read -r -a RATES <<< "${RATES[*]}"
SECONDS_PER_PHASE="${SECONDS_PER_PHASE:-60}"
OUT_DIR="${OUT_DIR:-load-test/results/full-stack}"
mkdir -p "$OUT_DIR"

sql() { docker compose exec -T postgres psql -U usage -d usage -At -c "$1"; }
recorded() { sql "SELECT count(*) FROM usage_events WHERE client_id = 'load-test'"; }
dropped() {
  curl -s 'http://localhost:9090/api/v1/query?query=sum(usage_events_dropped_total)' |
    python3 -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)'
}

echo "Warm-up (not recorded): 60 s at 100/s through each path"
k6 run --quiet -e TARGET=direct -e RATE=100 -e SECONDS=60 -e OUT=/dev/null load-test/full-stack.js
k6 run --quiet -e TARGET=gateway -e RATE=100 -e SECONDS=60 -e OUT=/dev/null load-test/full-stack.js
# Start the series only once the consumer has recorded the warm-up (at most 120 s).
for _ in $(seq 1 120); do
  [ "$(sql "SELECT count(*) FROM usage_events WHERE recorded_at > now() - interval '3 seconds'")" = 0 ] && break
  sleep 1
done

for rate in "${RATES[@]}"; do
  sleep 10
  k6 run --quiet -e TARGET=direct -e RATE="$rate" -e SECONDS="$SECONDS_PER_PHASE" \
    -e OUT="$OUT_DIR/direct-$rate.json" load-test/full-stack.js
  sleep 10
  before=$(recorded)
  k6 run --quiet -e TARGET=gateway -e RATE="$rate" -e SECONDS="$SECONDS_PER_PHASE" \
    -e OUT="$OUT_DIR/gateway-$rate.json" load-test/full-stack.js
  expected=$(( before + $(python3 -c "import json; print(int(json.load(open('$OUT_DIR/gateway-$rate.json'))['answered']))") ))
  # The usage consumer: how many requests were still unrecorded when the load stopped, and
  # how long it then took to record them all (at most 300 s).
  backlog=$(( expected - $(recorded) ))
  start=$(date +%s.%N)
  caught_up=true
  while [ "$(recorded)" -lt "$expected" ]; do
    if (( $(date +%s) - ${start%.*} > 300 )); then caught_up=false; break; fi
    sleep 0.5
  done
  catch_up=$(python3 -c "print(round($(date +%s.%N) - $start, 1))")
  printf '{"rate": %s, "backlogAtEnd": %s, "catchUpSeconds": %s, "caughtUp": %s, "recorded": %s, "expected": %s, "droppedEvents": %s}\n' \
    "$rate" "$backlog" "$catch_up" "$caught_up" "$(recorded)" "$expected" "$(dropped)" | tee "$OUT_DIR/consumer-$rate.json"
done
