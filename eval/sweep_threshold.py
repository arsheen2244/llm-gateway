"""Sweep the semantic cache's similarity threshold over the labelled prompt pairs.

For every pair in eval/cache-pairs.json, both prompts are embedded by the same
text-embeddings-inference service the gateway uses (design note 34), normalized the same way
the gateway normalizes them, and compared by cosine similarity. For each threshold:

  hit rate        share of "hit" pairs whose similarity >= threshold (higher is better)
  false-hit rate  share of "no_hit" pairs whose similarity >= threshold: the cache would
                  serve a wrong answer (lower is better)

Outputs, in --out (default results/):
  threshold-sweep.md    the table, plus the pairs each candidate threshold still gets wrong
  threshold-sweep.csv   the table as CSV
  threshold-sweep.png   hit rate and false-hit rate against threshold
  pair-similarities.csv every pair with its similarity

Refuses to report anything while a pair is unreviewed (the owner checks every label first).
--check-only embeds everything and checks the pipeline works, but prints no rates.

Usage: python eval/sweep_threshold.py [--url http://localhost:8082] [--out results] [--check-only]
Needs only the standard library, plus matplotlib for the plot.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

PAIRS_FILE = Path(__file__).with_name("cache-pairs.json")
THRESHOLDS = [round(0.70 + i * 0.01, 2) for i in range(30)]  # 0.70 .. 0.99
BATCH_SIZE = 32  # TEI's default maximum batch size
# Java's "\s" (no Unicode flag): the whitespace the gateway collapses (CacheKeys.normalize).
JAVA_WHITESPACE = re.compile(r"[ \t\n\x0b\f\r]+")


@dataclass(frozen=True)
class Pair:
    id: str
    expected: str  # "hit" or "no_hit"
    category: str
    a: str
    b: str
    reviewed: bool


@dataclass(frozen=True)
class Row:
    threshold: float
    hit_rate: float
    false_hit_rate: float
    hits: int
    false_hits: int


def normalize(text: str) -> str:
    """Same as the gateway: Unicode NFC, trim, collapse whitespace runs to one space."""
    return JAVA_WHITESPACE.sub(" ", unicodedata.normalize("NFC", text).strip())


def load_pairs(path: Path) -> list[Pair]:
    data = json.loads(path.read_text(encoding="utf-8"))
    pairs = [Pair(p["id"], p["expected"], p["category"], p["a"], p["b"], bool(p.get("reviewed")))
             for p in data["pairs"]]
    ids = [p.id for p in pairs]
    if len(ids) != len(set(ids)):
        raise ValueError("Pair ids must be unique")
    bad = [p.id for p in pairs if p.expected not in ("hit", "no_hit")]
    if bad:
        raise ValueError(f"'expected' must be 'hit' or 'no_hit': {bad}")
    return pairs


def unreviewed(pairs: list[Pair]) -> list[str]:
    return [p.id for p in pairs if not p.reviewed]


def dot(a: list[float], b: list[float]) -> float:
    """Vectors are unit length (normalize=true), so the dot product is the cosine similarity."""
    if len(a) != len(b):
        raise ValueError("Vector lengths differ")
    return math.fsum(x * y for x, y in zip(a, b))


def sweep(pairs: list[Pair], similarity: dict[str, float], thresholds: list[float]) -> list[Row]:
    should_hit = [similarity[p.id] for p in pairs if p.expected == "hit"]
    must_not = [similarity[p.id] for p in pairs if p.expected == "no_hit"]
    rows = []
    for t in thresholds:
        hits = sum(s >= t for s in should_hit)
        false_hits = sum(s >= t for s in must_not)
        rows.append(Row(t, hits / max(len(should_hit), 1), false_hits / max(len(must_not), 1), hits, false_hits))
    return rows


def wrong_at(pairs: list[Pair], similarity: dict[str, float], threshold: float) -> tuple[list[Pair], list[Pair]]:
    """(false hits: no_hit pairs served anyway, missed: hit pairs not served) at this threshold."""
    false_hits = [p for p in pairs if p.expected == "no_hit" and similarity[p.id] >= threshold]
    missed = [p for p in pairs if p.expected == "hit" and similarity[p.id] < threshold]
    key = lambda p: -similarity[p.id]
    return sorted(false_hits, key=key), sorted(missed, key=key)


def candidates(rows: list[Row]) -> dict[str, Row]:
    """Thresholds worth discussing: the best hit rate with no false hits, and with at most 1%."""
    picks = {}
    for label, limit in (("zero false hits", 0.0), ("false hits <= 1%", 0.01)):
        allowed = [r for r in rows if r.false_hit_rate <= limit]
        if allowed:
            best = max(allowed, key=lambda r: (r.hit_rate, -r.threshold))
            picks[label] = best
    return picks


# ---- embedding service ----

def wait_until_healthy(url: str, timeout_s: float = 180) -> None:
    deadline = time.monotonic() + timeout_s
    while True:
        try:
            with urllib.request.urlopen(f"{url}/health", timeout=5) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        if time.monotonic() > deadline:
            raise RuntimeError(f"Embedding service at {url} never became healthy")
        time.sleep(2)


def embed_all(url: str, texts: list[str]) -> dict[str, list[float]]:
    vectors: dict[str, list[float]] = {}
    for start in range(0, len(texts), BATCH_SIZE):
        batch = texts[start:start + BATCH_SIZE]
        body = json.dumps({"inputs": batch, "normalize": True, "truncate": False}).encode("utf-8")
        request = urllib.request.Request(f"{url}/embed", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=60) as response:
            answer = json.loads(response.read())
        if len(answer) != len(batch):
            raise RuntimeError(f"Asked for {len(batch)} embeddings, got {len(answer)}")
        vectors.update(zip(batch, answer))
    return vectors


# ---- outputs ----

def write_csv(rows: list[Row], path: Path) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["threshold", "hit_rate", "false_hit_rate", "hits", "false_hits"])
        for r in rows:
            writer.writerow([f"{r.threshold:.2f}", f"{r.hit_rate:.4f}", f"{r.false_hit_rate:.4f}", r.hits, r.false_hits])


def write_pairs_csv(pairs: list[Pair], similarity: dict[str, float], path: Path) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "expected", "category", "similarity", "a", "b"])
        for p in sorted(pairs, key=lambda p: -similarity[p.id]):
            writer.writerow([p.id, p.expected, p.category, f"{similarity[p.id]:.4f}", p.a, p.b])


def markdown(pairs: list[Pair], similarity: dict[str, float], rows: list[Row], model: str) -> str:
    n_hit = sum(p.expected == "hit" for p in pairs)
    lines = [
        "# Semantic cache threshold sweep",
        "",
        f"{len(pairs)} reviewed pairs ({n_hit} should hit, {len(pairs) - n_hit} must not), "
        f"embedded with `{model}`, cosine similarity of each pair.",
        "",
        "| Threshold | Hit rate | False-hit rate | Hits | False hits |",
        "|---|---|---|---|---|",
    ]
    lines += [f"| {r.threshold:.2f} | {r.hit_rate:.1%} | {r.false_hit_rate:.1%} | {r.hits} | {r.false_hits} |"
              for r in rows]
    for label, row in candidates(rows).items():
        false_hits, missed = wrong_at(pairs, similarity, row.threshold)
        lines += ["", f"## Threshold {row.threshold:.2f} ({label})", "",
                  f"Hit rate {row.hit_rate:.1%}, false-hit rate {row.false_hit_rate:.1%}.", "",
                  f"**Wrong answers served ({len(false_hits)}):**", ""]
        lines += [f"- `{p.id}` {similarity[p.id]:.4f}: \"{p.a}\" → \"{p.b}\"" for p in false_hits] or ["- none"]
        lines += ["", f"**Paraphrases missed ({len(missed)}), most similar first:**", ""]
        lines += [f"- `{p.id}` {similarity[p.id]:.4f}: \"{p.a}\" / \"{p.b}\"" for p in missed] or ["- none"]
    return "\n".join(lines) + "\n"


def plot(rows: list[Row], path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(figsize=(8, 4.5))
    xs = [r.threshold for r in rows]
    ax.plot(xs, [r.hit_rate * 100 for r in rows], marker="o", markersize=3, label="Hit rate (paraphrases served)")
    ax.plot(xs, [r.false_hit_rate * 100 for r in rows], marker="o", markersize=3,
            label="False-hit rate (wrong answers served)")
    ax.set_xlabel("Similarity threshold")
    ax.set_ylabel("% of pairs")
    ax.set_ylim(0, 100)
    ax.grid(alpha=0.3)
    ax.legend()
    ax.set_title("Semantic cache: hit rate vs false-hit rate")
    fig.tight_layout()
    fig.savefig(path, dpi=150)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:8082", help="text-embeddings-inference base URL")
    parser.add_argument("--pairs", type=Path, default=PAIRS_FILE)
    parser.add_argument("--out", type=Path, default=Path("results"))
    parser.add_argument("--model", default="BAAI/bge-small-en-v1.5", help="model name, for the report only")
    parser.add_argument("--check-only", action="store_true", help="embed everything, report no rates")
    args = parser.parse_args(argv)

    pairs = load_pairs(args.pairs)
    pending = unreviewed(pairs)
    if pending and not args.check_only:
        print(f"Refusing to report results: {len(pending)} of {len(pairs)} pairs are not reviewed yet "
              f"(first: {', '.join(pending[:5])}). Set \"reviewed\": true after checking each label.")
        return 2

    wait_until_healthy(args.url)
    texts = sorted({normalize(t) for p in pairs for t in (p.a, p.b)})
    vectors = embed_all(args.url, texts)
    similarity = {p.id: dot(vectors[normalize(p.a)], vectors[normalize(p.b)]) for p in pairs}

    if args.check_only:
        print(f"Check only: embedded {len(texts)} texts for {len(pairs)} pairs "
              f"({len(next(iter(vectors.values())))} dimensions). No rates reported "
              f"({len(pending)} pairs unreviewed).")
        return 0

    rows = sweep(pairs, similarity, THRESHOLDS)
    args.out.mkdir(parents=True, exist_ok=True)
    write_csv(rows, args.out / "threshold-sweep.csv")
    write_pairs_csv(pairs, similarity, args.out / "pair-similarities.csv")
    report = markdown(pairs, similarity, rows, args.model)
    (args.out / "threshold-sweep.md").write_text(report, encoding="utf-8")
    plot(rows, args.out / "threshold-sweep.png")
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
