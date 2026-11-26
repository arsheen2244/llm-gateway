"""Turns load-test/full_stack.sh results into the week-5 benchmark table (design note 43).

For each rate: the gateway's run is valid only if it had no errors and k6 dropped no requests
(it kept up with the rate). Added latency is a percentile through the gateway minus the same
percentile straight to the mock at the same rate: a difference of percentiles, not a percentile
of per-request differences. Prints Markdown and GitHub ::notice lines.

Usage: python3 load-test/full_stack_report.py [results-dir]   (default load-test/results/full-stack)
"""

import json
import sys
from pathlib import Path

DIR = Path(sys.argv[1] if len(sys.argv) > 1 else "load-test/results/full-stack")


def load(name):
    path = DIR / name
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def sustained(run):
    return run["errorRate"] == 0 and run["droppedIterations"] == 0


def main():
    rates = sorted(int(p.stem.split("-")[1]) for p in DIR.glob("gateway-*.json"))
    lines = ["| Rate (req/s) | Achieved | Errors | Dropped | Added p50 | Added p95 | Added p99 "
             "| Gateway p99 | Mock p99 | Consumer backlog at end | Caught up in |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    best = None
    for rate in rates:
        g, d, c = load(f"gateway-{rate}.json"), load(f"direct-{rate}.json"), load(f"consumer-{rate}.json")
        if not sustained(d):
            lines.append(f"| {rate} | mock alone could not sustain this rate; not a gateway result |"
                         + " |" * 9)
            continue
        ok = sustained(g)
        added = [f"{g[k] - d[k]:+.1f} ms" if ok else "n/a" for k in ("p50", "p95", "p99")]
        consumer = (f"{c['backlogAtEnd']} | {c['catchUpSeconds']} s" if c and c["caughtUp"]
                    else f"{c['backlogAtEnd'] if c else '?'} | not within 300 s")
        lines.append(f"| {rate} | {g['achievedRate']:.1f} | {g['errorRate'] * 100:.2f}% | {g['droppedIterations']} | "
                     + " | ".join(added) + f" | {g['p99']:.1f} ms | {d['p99']:.1f} ms | {consumer} |")
        if ok:
            best = rate
    lines.append("")
    lines.append(f"Highest rate sustained (no errors, no dropped requests): {best}/s" if best
                 else "No rate was sustained.")
    for line in lines:
        print(line)
    for line in lines[2:]:
        print(f"::notice title=full-stack load test::{line}")


if __name__ == "__main__":
    main()
