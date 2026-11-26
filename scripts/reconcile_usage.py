"""Reconciliation on the running Compose stack (design note 41).

Sends a known number of requests through nginx, kills the usage consumer (SIGKILL) partway
through, keeps sending while it is down, starts it again, and checks that PostgreSQL and
GET /v1/usage hold exactly what the clients saw in their answers: every request once, every
token, and the exact cost.

Usage (from the repo root, stack up): python3 scripts/reconcile_usage.py [gateway-url]
Standard library only. Exits 1 on any difference.
"""

import json
import subprocess
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal

GATEWAY = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
KEY = "load-test-key"  # public demo key with high limits
CLIENT = "load-test"
PHASE = 300  # requests per phase: consumer up, down, up again
WORKERS = 8
# A model name nothing else uses (the mock accepts any mock-* name), with its own price.
MODEL = f"mock-reconcile-{time.time_ns()}"
INPUT_PRICE, OUTPUT_PRICE = Decimal("1.5"), Decimal("6.25")  # made up, per million tokens


def run(*command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()


def sql(query):
    return run("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "usage", "-d", "usage",
               "-At", "-v", "ON_ERROR_STOP=1", "-c", query)


def recorded():
    return int(sql(f"SELECT count(*) FROM usage_events WHERE model = '{MODEL}'"))


def http(path, body=None):
    request = urllib.request.Request(
        GATEWAY + path, data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode()


def chat(i):
    """One request (every other one a stream); returns the tokens its answer reported."""
    stream = i % 2 == 0
    body = {"model": MODEL, "messages": [{"role": "user", "content": "word " * (i % 17 + 1)}]}
    if stream:
        body |= {"stream": True, "stream_options": {"include_usage": True}}
    answer = http("/v1/chat/completions", body)
    if not stream:
        usage = json.loads(answer)["usage"]
    else:
        chunks = [json.loads(line[6:]) for line in answer.splitlines()
                  if line.startswith("data: {")]
        usage = [c["usage"] for c in chunks if c.get("usage")][-1]
    return usage["prompt_tokens"], usage["completion_tokens"]


def send(first, count):
    """Sends requests [first, first + count); any failed request fails the run."""
    with ThreadPoolExecutor(WORKERS) as pool:
        return list(pool.map(chat, range(first, first + count)))


def wait_for(condition, seconds, what):
    deadline = time.monotonic() + seconds
    while not condition():
        if time.monotonic() > deadline:
            fail(f"not within {seconds} s: {what}")
        time.sleep(0.5)


def fail(message):
    print(f"FAIL: {message}")
    print(run("docker", "compose", "logs", "--tail", "40", "usage-consumer"))
    sys.exit(1)


def check(what, actual, expected):
    print(f"  {what}: {actual} (expected {expected})")
    if actual != expected:
        fail(f"{what}: {actual}, expected {expected}")


def usage_total():
    return json.loads(http("/v1/usage"))["total"]


def consumer_running():
    return run("docker", "compose", "ps", "--status", "running", "-q", "usage-consumer") != ""


def main():
    sql(f"INSERT INTO model_prices VALUES ('mock-openai', '{MODEL}', '2020-01-01', {INPUT_PRICE}, {OUTPUT_PRICE})")
    before = usage_total()

    print(f"1) {PHASE} requests; the consumer is killed partway through")
    with ThreadPoolExecutor(1) as background:
        sending = background.submit(send, 0, PHASE)
        wait_for(lambda: recorded() >= PHASE // 4, 60, "a quarter of the first phase recorded")
        run("docker", "compose", "kill", "-s", "SIGKILL", "usage-consumer")
        time.sleep(2)  # a commit PostgreSQL had already received may still finish
        at_kill = recorded()
        seen = sending.result()
    if consumer_running():
        fail("the consumer is still running after the kill")
    if not 0 < at_kill < PHASE:
        fail(f"the kill was not partway through: {at_kill} of {PHASE} recorded")
    print(f"  killed with {at_kill} of {PHASE} recorded")

    print(f"2) {PHASE} more requests while the consumer is down (all must succeed)")
    seen += send(PHASE, PHASE)
    while_down = recorded()
    check("recorded while down", while_down, at_kill)

    print(f"3) Consumer started again, {PHASE} more requests")
    run("docker", "compose", "start", "usage-consumer")
    seen += send(2 * PHASE, PHASE)
    wait_for(lambda: recorded() >= len(seen), 180, f"{len(seen)} events recorded")
    time.sleep(5)  # would anything extra still arrive?

    prompt, completion = sum(p for p, _ in seen), sum(c for _, c in seen)
    cost = (prompt * INPUT_PRICE + completion * OUTPUT_PRICE) / 1_000_000
    print("4) PostgreSQL against what the clients saw")
    rows, p, c, usd, other = sql(
        f"SELECT count(*), sum(prompt_tokens), sum(completion_tokens), sum(cost_usd), "
        f"count(*) FILTER (WHERE status <> 'SUCCESS' OR client_id <> '{CLIENT}') "
        f"FROM usage_events WHERE model = '{MODEL}'").split("|")
    check("requests", int(rows), 3 * PHASE)
    check("prompt tokens", int(p), prompt)
    check("completion tokens", int(c), completion)
    check("cost in USD", Decimal(usd), cost)
    check("events with another status or client", int(other), 0)

    print("5) GET /v1/usage, after minus before")
    after = usage_total()
    for field, expected in [("requests", 3 * PHASE), ("successful_requests", 3 * PHASE),
                            ("prompt_tokens", prompt), ("completion_tokens", completion),
                            ("unpriced_requests", 0)]:
        check(field, after[field] - before[field], expected)
    check("cost_usd", Decimal(after["cost_usd"]) - Decimal(before["cost_usd"]), cost)

    wrong = sql("SELECT count(*) FROM (SELECT client_id, (occurred_at AT TIME ZONE 'UTC')::date AS day, "
                "count(*) AS n, coalesce(sum(cost_usd), 0) AS cost FROM usage_events GROUP BY 1, 2) e "
                "FULL JOIN usage_daily d USING (client_id, day) "
                "WHERE e.n IS DISTINCT FROM d.requests OR e.cost IS DISTINCT FROM d.cost_usd")
    check("client-days where the totals differ from the events", int(wrong), 0)
    print(f"Reconciliation passed: {len(seen)} requests, nothing lost, nothing double-counted.")


if __name__ == "__main__":
    main()
