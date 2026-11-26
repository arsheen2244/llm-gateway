"""Where did the usage events go? Counts them at every stage, after a load test.

  emitted   events the usage interceptor created (gateway_requests_seconds_count, per gateway)
  sent      events Kafka acknowledged, dropped / queued (the sink's own counters, per gateway)
  in Kafka  records in usage-events and usage-events-dlt (end offsets)
  recorded  rows in usage_events

Also: container state (exited, OOM-killed, restarted), JVM restarts seen by Prometheus, and the
warnings and errors the gateways and the consumer logged.

Written to find where events went missing under overload (design note 44); the full-stack load
test runs it after every measurement. A gateway that restarted started its counters over, so its
per-stage numbers cover only its last JVM.

Usage (from the repo root, stack up): python3 scripts/diagnose_usage.py
Standard library only.
"""

import json
import re
import subprocess
import urllib.parse
import urllib.request
from collections import Counter

PROMETHEUS = "http://localhost:9090"
GATEWAYS = ["gateway-1", "gateway-2", "gateway-3"]


def run(*command):
    result = subprocess.run(command, capture_output=True, text=True)
    return (result.stdout + result.stderr).strip()


def prom(query):
    """{instance: value}, using the last value each gateway reported (even if it is gone now)."""
    url = f"{PROMETHEUS}/api/v1/query?" + urllib.parse.urlencode({"query": query})
    with urllib.request.urlopen(url, timeout=10) as response:
        result = json.load(response)["data"]["result"]
    return {r["metric"].get("instance", "all"): float(r["value"][1]) for r in result}


def last(metric):
    return prom(f"sum by (instance) (last_over_time({metric}[3h]))")


def kafka_total(topic):
    out = run("docker", "compose", "exec", "-T", "kafka", "/opt/kafka/bin/kafka-get-offsets.sh",
              "--bootstrap-server", "localhost:9092", "--topic", topic)
    offsets = [int(m) for m in re.findall(r"^[\w-]+:\d+:(\d+)$", out, re.MULTILINE)]
    if offsets:
        return sum(offsets)
    return "0 (no such topic)" if "does not exist" in out or "Could not match any topic" in out or not out else f"? ({out[:200]})"


def sql(query):
    return run("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "usage", "-d", "usage", "-At",
               "-c", query)


def section(title):
    print(f"\n=== {title}")


def main():
    section("Containers")
    print(run("docker", "compose", "ps", "-a", "--format",
              "table {{.Service}}\t{{.State}}\t{{.Status}}\t{{.ExitCode}}"))
    for service in GATEWAYS + ["usage-consumer", "kafka"]:
        cid = run("docker", "compose", "ps", "-a", "-q", service)
        print(service, run("docker", "inspect", "-f",
                           "oom-killed={{.State.OOMKilled}} restarts={{.RestartCount}} "
                           "started={{.State.StartedAt}} exit={{.State.ExitCode}}", cid))

    section("JVM starts seen by Prometheus (more than one value = a restart)")
    print(prom("count by (instance) (count_values by (instance) (\"start\", "
               "process_start_time_seconds{job=\"gateway\"}))"))
    print("up now:", prom("up{job=\"gateway\"}"))

    section("Events per stage, per gateway (last reported values)")
    emitted = last("gateway_requests_seconds_count")
    sent, dropped, queued = (last(f"usage_events_{m}") for m in ("sent_total", "dropped_total", "queued"))
    for instance in sorted(emitted):
        e, s, d, q = (int(x.get(instance, 0)) for x in (emitted, sent, dropped, queued))
        print(f"{instance}: emitted {e}, sent {s}, dropped {d}, queued {q}, unaccounted {e - s - d - q}")
    totals = [int(sum(x.values())) for x in (emitted, sent, dropped, queued)]
    print("all: emitted {}, sent {}, dropped {}, queued {}, unaccounted {}".format(
        *totals, totals[0] - totals[1] - totals[2] - totals[3]))
    print("in Kafka: usage-events", kafka_total("usage-events"), "| usage-events-dlt",
          kafka_total("usage-events-dlt"))
    print("recorded:", sql("SELECT count(*) FROM usage_events"))
    print(run("docker", "compose", "exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh",
              "--bootstrap-server", "localhost:9092", "--describe", "--group", "usage-consumer"))

    section("Gateway warnings and errors (counts by message)")
    for service in GATEWAYS:
        lines = [l for l in run("docker", "compose", "logs", "--no-color", service).splitlines()
                 if re.search(r'"level":"(WARN|ERROR)"|OutOfMemory|Killed|fatal', l)]
        counts = Counter(re.sub(r"\d+", "N", l.split("|", 1)[-1])[:220] for l in lines)
        print(f"-- {service}: {len(lines)} lines")
        for message, n in counts.most_common(15):
            print(f"{n:7d}  {message}")

    section("Consumer warnings and errors")
    lines = [l for l in run("docker", "compose", "logs", "--no-color", "usage-consumer").splitlines()
             if re.search(r"WARN|ERROR|Exception", l)]
    print(len(lines), "lines")
    print("\n".join(lines[:40]))


if __name__ == "__main__":
    main()
