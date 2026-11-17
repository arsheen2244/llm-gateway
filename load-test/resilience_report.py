"""Turns a resilience.js phase result plus mock /stats snapshots into the week-3 numbers
(design note 31), printed as a table and as GitHub ::notice annotations (public on the run
page, no sign-in needed).

Usage:
  python3 resilience_report.py --label "outage, failover" --result resilience-failover.json \
      --stats mock-down before.json after.json --stats mock-backup before.json after.json
"""
import argparse
import json


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--result", required=True)
    parser.add_argument("--stats", nargs=3, action="append", default=[],
                        metavar=("MOCK", "BEFORE", "AFTER"))
    args = parser.parse_args()

    r = load(args.result)
    requests = r["requests"]
    lines = [f"[{args.label}] client requests {requests}, ok {r['ok']}, failed {r['failed']}, "
             f"success {r['successRate'] * 100:.2f}%",
             f"[{args.label}] latency of successful requests (ms): p50 {r['p50']:.0f}, "
             f"p95 {r['p95']:.0f}, p99 {r['p99']:.0f}, max {r['max']:.0f}"]

    upstream_total = 0
    for mock, before, after in args.stats:
        delta = load(after)["requests"] - load(before)["requests"]
        upstream_total += delta
        lines.append(f"[{args.label}] calls received by {mock}: {delta} "
                     f"({delta / requests:.3f} per client request)" if requests else "")
    if args.stats and requests:
        lines.append(f"[{args.label}] upstream calls per client request (all providers): "
                     f"{upstream_total / requests:.3f} (extra volume {(upstream_total / requests - 1) * 100:+.1f}%)")

    for line in lines:
        if line:
            print(line)
            print(f"::notice title={args.label}::{line}")


if __name__ == "__main__":
    main()
