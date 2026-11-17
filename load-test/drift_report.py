"""Turns k6's JSON output from rate-limit-drift.js into drift numbers (design note 24).

For each client it finds the most requests (or tokens) admitted in ANY 60-second window,
sliding one second at a time over the run, and compares that with the configured limit.
Results are printed as a table, as GitHub annotations (::notice, visible on the run page
without signing in), and written to a Markdown file.

Usage: python3 drift_report.py drift.json --backend redis [--markdown out.md]
"""
import argparse
import json
from datetime import datetime

# What each client is limited to (application-local.yml) and which metric measures it.
CLIENTS = {
    "requests": {"metric": "admitted_requests", "limit": 300, "unit": "requests"},
    "tokens": {"metric": "admitted_tokens", "limit": 3000, "unit": "tokens"},
}
WINDOW_S = 60


def parse_time(value):
    # k6 writes RFC 3339 timestamps, sometimes with nanoseconds; keep microseconds.
    if "." in value:
        head, rest = value.split(".", 1)
        digits = "".join(ch for ch in rest if ch.isdigit())
        zone = rest[len(digits):]
        value = f"{head}.{digits[:6]}{zone}"
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def read_points(path):
    """{(metric, client): [(time, value), ...]} plus counts of other outcomes."""
    points, other = {}, {"rejected_requests": 0, "unexpected_responses": 0}
    with open(path, encoding="utf-8") as f:
        for line in f:
            entry = json.loads(line)
            if entry.get("type") != "Point":
                continue
            metric, data = entry["metric"], entry["data"]
            if metric in other:
                other[metric] += int(data["value"])
            elif metric in ("admitted_requests", "admitted_tokens"):
                client = (data.get("tags") or {}).get("client")
                points.setdefault((metric, client), []).append((parse_time(data["time"]), data["value"]))
    return points, other


def windows(samples):
    """Totals of every 60 s window starting at each whole second of the run."""
    samples.sort()
    start, end = samples[0][0], samples[-1][0]
    totals, lo, hi, running = [], 0, 0, 0.0
    t = start
    while t + WINDOW_S <= end + 1e-9:
        while hi < len(samples) and samples[hi][0] < t + WINDOW_S:
            running += samples[hi][1]
            hi += 1
        while lo < hi and samples[lo][0] < t:
            running -= samples[lo][1]
            lo += 1
        totals.append(running)
        t += 1.0
    return totals


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("path")
    parser.add_argument("--backend", required=True)
    parser.add_argument("--markdown")
    args = parser.parse_args()

    points, other = read_points(args.path)
    rows = []
    for client, spec in CLIENTS.items():
        samples = points.get((spec["metric"], client), [])
        totals = windows(samples) if samples else []
        if not totals:
            rows.append((client, spec, None, None, 0))
            continue
        rows.append((client, spec, max(totals), sum(totals) / len(totals), len(totals)))

    lines = [f"Rate-limit drift, backend = {args.backend}",
             f"{'client':10} {'limit/min':>10} {'max any 60s':>12} {'drift':>8} {'mean 60s':>10} {'windows':>8}"]
    md = [f"### Rate-limit drift: `{args.backend}`", "",
          "| Client | Limit per minute | Max admitted in any 60 s | Drift | Mean per 60 s | Windows |",
          "|---|---|---|---|---|---|"]
    for client, spec, peak, mean, count in rows:
        if peak is None:
            lines.append(f"{client:10} {spec['limit']:>10} {'no data':>12}")
            md.append(f"| {client} | {spec['limit']} {spec['unit']} | no data | | | |")
            print(f"::warning title=drift {args.backend} {client}::no admitted samples (run too short?)")
            continue
        drift = (peak / spec["limit"] - 1) * 100
        lines.append(f"{client:10} {spec['limit']:>10} {peak:>12.0f} {drift:>+7.1f}% {mean:>10.1f} {count:>8}")
        md.append(f"| {client} | {spec['limit']} {spec['unit']} | {peak:.0f} | {drift:+.1f}% | {mean:.1f} | {count} |")
        print(f"::notice title=drift {args.backend} {client}::limit {spec['limit']} {spec['unit']}/min; "
              f"max in any 60 s window {peak:.0f} ({drift:+.1f}%); mean per 60 s {mean:.1f}; {count} windows")
    lines.append(f"rejected (429): {other['rejected_requests']}   unexpected responses: {other['unexpected_responses']}")
    md += ["", f"429 responses: {other['rejected_requests']}. Unexpected responses: {other['unexpected_responses']}."]
    print("\n".join(lines))
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as f:
            f.write("\n".join(md) + "\n")


if __name__ == "__main__":
    main()
