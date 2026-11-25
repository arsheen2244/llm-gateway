"""Checks the metrics and the Grafana dashboard on the running Compose stack (design note 42).

Sends traffic of every kind the dashboard shows, then checks that:
  - Prometheus counted exactly the successes, rejections, cache hits and failures the clients saw;
  - every panel's query, taken from the dashboard file itself, returns data, and the panels show
    what happened (rejections, errors, cache hits, an open breaker);
  - Grafana serves the dashboard and can query Prometheus;
  - metrics are not reachable through nginx.

Opens the mock-anthropic breaker, so run it after other checks.
Usage (from the repo root, stack up): python3 scripts/check_metrics.py [gateway-url]
Standard library only.
"""

import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

GATEWAY = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
PROMETHEUS = "http://localhost:9090"
GRAFANA = "http://localhost:3000"
DASHBOARD = Path(__file__).parent.parent / "deploy/grafana/dashboards/llm-gateway.json"


def fail(message):
    print(f"FAIL: {message}")
    sys.exit(1)


def get(url, body=None):
    """(status, text); never raises for an HTTP error status."""
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def query(expr):
    status, text = get(f"{PROMETHEUS}/api/v1/query?" + urllib.parse.urlencode({"query": expr}))
    if status != 200:
        fail(f"Prometheus answered {status} to {expr}: {text}")
    return json.loads(text)["data"]["result"]


def number(expr):
    result = query(expr)
    return float(result[0]["value"][1]) if result else 0.0


def chat(args):
    """One chat request; returns (HTTP status, X-Cache)."""
    key, body = args
    request = urllib.request.Request(f"{GATEWAY}/v1/chat/completions", data=json.dumps(body).encode(), headers={
        "Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
            return response.status, response.headers.get("X-Cache")
    except urllib.error.HTTPError as e:
        e.read()
        return e.code, e.headers.get("X-Cache")


def message(text, model="mock-gpt", **more):
    return {"model": model, "messages": [{"role": "user", "content": text}], **more}


def traffic(round_):
    """Plain, streamed, cacheable, over-limit and failing requests."""
    load, drift = "load-test-key", "drift-requests-key"
    requests = [(load, message(f"plain {i}")) for i in range(20)]
    requests += [(load, message(f"stream {i}", stream=True)) for i in range(20)]
    requests += [(load, message(f"cached {time.time_ns() if i % 4 == 0 else round_}", temperature=0))
                 for i in range(40)]
    requests += [(drift, message(f"limited {i}")) for i in range(200)]  # this key: 300 per minute
    requests += [(load, message("fails", model="mock-claude-error-503")) for _ in range(60)]
    with ThreadPoolExecutor(16) as pool:
        return list(pool.map(chat, requests))


COUNTS = {
    "success": 'sum(gateway_requests_seconds_count{status="success"})',
    "rate_limited": 'sum(gateway_requests_seconds_count{status="rate_limited"})',
    "failed": 'sum(gateway_requests_seconds_count{status=~"provider_error|error"})',
    "cache_hits": 'sum(gateway_requests_seconds_count{cache=~"exact|semantic"})',
}


def counts():
    time.sleep(12)  # at least two scrapes, so every instance's latest numbers are in
    return {name: number(expr) for name, expr in COUNTS.items()}


def seen_by_clients(answers):
    statuses = Counter(status for status, _ in answers)
    return {
        "success": statuses[200],
        "rate_limited": statuses[429],
        "failed": sum(n for status, n in statuses.items() if status >= 500),
        "cache_hits": sum(1 for _, cache in answers if cache in ("exact", "semantic")),
    }


def panel_values(dashboard):
    """Runs every panel's queries; {title: [(labels, value), ...]}."""
    values = {}
    for panel in dashboard["panels"]:
        rows = []
        for target in panel["targets"]:
            expr = target["expr"].replace("$__rate_interval", "1m").replace("$__range", "15m")
            result = query(expr)
            if not result:
                fail(f"panel '{panel['title']}' has no data for: {expr}")
            rows += [(r["metric"], float(r["value"][1])) for r in result]
        if any(math.isnan(v) for _, v in rows):
            fail(f"panel '{panel['title']}' shows NaN")
        values[panel["title"]] = rows
        print(f"  {panel['title']}: " + ", ".join(
            f"{','.join(labels.values()) or 'value'}={value:.4g}" for labels, value in rows))
    return values


def main():
    deadline = time.monotonic() + 90
    while number('sum(up{job="gateway"})') != 3:
        if time.monotonic() > deadline:
            fail("Prometheus does not see 3 gateways")
        time.sleep(2)

    before = counts()
    answers = []
    for round_ in (1, 2):
        answers += traffic(round_)
        time.sleep(8)  # spread over several scrapes, so rates have something to measure
    after = counts()

    print("Counted by Prometheus against what clients saw:")
    expected = seen_by_clients(answers)
    print(f"  (client statuses: {dict(Counter(status for status, _ in answers))})")
    for name, clients in expected.items():
        counted = round(after[name] - before[name])
        print(f"  {name}: {counted} (clients: {clients})")
        if counted != clients:
            fail(f"{name}: Prometheus {counted}, clients {clients}")
        if clients == 0:
            fail(f"the traffic produced no {name}, so this check proves nothing")

    print("Every panel's query:")
    shown = panel_values(json.loads(DASHBOARD.read_text(encoding="utf-8")))
    only = {title: rows[0][1] for title, rows in shown.items()}
    breakers = {labels["provider"]: value for labels, value in shown["Circuit breakers"]}
    for what, ok in [
        ("rejections above 0", only["Rate-limit rejections"] > 0),
        ("error rate between 0 and 1", 0 < only["Error rate"] < 1),
        ("cache hit rate between 0 and 1", 0 < only["Cache hit rate"] < 1),
        ("the failing provider's breaker left closed", breakers["mock-anthropic"] > 0),
        ("the healthy provider's breaker closed", breakers["mock-openai"] == 0),
        ("p50 <= p95 <= p99", shown["Latency"][2][1] <= shown["Latency"][1][1] <= shown["Latency"][0][1]),
        ("no usage events lost", only["Usage events lost"] == 0),
        ("3 gateways up", only["Gateways up"] == 3),
    ]:
        if not ok:
            fail(f"dashboard: expected {what}")

    status, text = get(f"{GRAFANA}/api/dashboards/uid/llm-gateway")
    if status != 200 or len(json.loads(text)["dashboard"]["panels"]) != len(shown):
        fail(f"Grafana does not serve the dashboard ({status})")
    status, text = get(f"{GRAFANA}/api/ds/query", {"from": "now-5m", "to": "now", "queries": [
        {"refId": "A", "datasource": {"uid": "prometheus"}, "expr": "up", "instant": True}]})
    if status != 200 or not json.loads(text)["results"]["A"].get("frames"):
        fail(f"Grafana can't query Prometheus ({status}): {text[:300]}")
    status, _ = get(f"{GATEWAY}/actuator/prometheus")
    if status != 404:
        fail(f"metrics are reachable through nginx ({status})")
    print("Metrics check passed.")


if __name__ == "__main__":
    main()
