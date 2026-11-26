"""What the gateways went through during one load-test phase, from Prometheus (design note 45).

Per gateway, over the last SECONDS (plus a margin for the 5 s scrape interval):
  peakInFlight   most requests in flight that an arriving request saw (the summary's _max)
  peakHeapMb     most heap in use, and heapMaxMb, the heap's limit
  restarts       JVM restarts (process_start_time_seconds changed)
  minUp          0 if Prometheus could not scrape the gateway at some point

Used to choose gateway.overload.max-in-flight, and to show that no gateway ran out of heap.

Usage: python3 load-test/overload_snapshot.py SECONDS OUT.json      Standard library only.
"""

import json
import sys
import urllib.parse
import urllib.request

PROMETHEUS = "http://localhost:9090"
MARGIN_SECONDS = 15


def prom(query):
    """{instance: value} for an instant query."""
    url = f"{PROMETHEUS}/api/v1/query?" + urllib.parse.urlencode({"query": query})
    with urllib.request.urlopen(url, timeout=10) as response:
        result = json.load(response)["data"]["result"]
    return {r["metric"].get("instance", "all"): float(r["value"][1]) for r in result}


def snapshot(seconds):
    window = f"{seconds + MARGIN_SECONDS}s"
    gw = 'job="gateway"'
    queries = {
        "peakInFlight": f"max by (instance) (max_over_time(gateway_requests_in_flight_at_arrival_max{{{gw}}}[{window}]))",
        "peakHeapMb": f"max_over_time(sum by (instance) (jvm_memory_used_bytes{{{gw},area=\"heap\"}})[{window}:5s]) / 1048576",
        "heapMaxMb": f"sum by (instance) (jvm_memory_max_bytes{{{gw},area=\"heap\"}} > 0) / 1048576",
        "restarts": f"changes(process_start_time_seconds{{{gw}}}[{window}])",
        "minUp": f"min_over_time(up{{{gw}}}[{window}])",
    }
    per_instance = {}
    for name, query in queries.items():
        for instance, value in prom(query).items():
            per_instance.setdefault(instance, {})[name] = round(value)
    return per_instance


def main():
    seconds, out = int(sys.argv[1]), sys.argv[2]
    per_instance = snapshot(seconds)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(per_instance, f, indent=2, sort_keys=True)
    for instance, values in sorted(per_instance.items()):
        print(f"  {instance}: " + ", ".join(f"{k} {v}" for k, v in sorted(values.items())))


if __name__ == "__main__":
    main()
