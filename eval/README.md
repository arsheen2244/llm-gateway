# Semantic cache evaluation

`cache-pairs.json` holds 200 labelled prompt pairs used to choose the semantic cache's
similarity threshold (design note 36):

| `expected` | Meaning | Count | Categories |
|---|---|---|---|
| `hit` | `b` means the same as `a`; serving `a`'s cached answer to `b` is correct | 100 | programming, general knowledge, how-to, definitions, math, wording and typos |
| `no_hit` | `b` looks like `a` but needs a different answer; serving `a`'s answer would be wrong | 100 | numbers, negation, entities, units and direction, dates, code details, opposites, audience and task |

Every pair is judged as a single-turn user message at temperature 0, from the same client,
same model, no system prompt: the only case the semantic layer handles (design note 32).

## Review before any results

**Status (2026-11-18): reviewed by Claude, at the owner's request.** The owner delegated the
review instead of doing it. Claude re-read all 200 pairs, reworded three ambiguous paraphrases
(`hit-045`, `hit-063`, `hit-072`) so both prompts ask for the same answer, and flipped no labels.
The labels are therefore Claude's own judgement, checked once, not an independent human review;
results built on them say so.

The labels are a **draft written by Claude**. Before any result is reported, the owner reads
every pair and:

1. fixes `expected` wherever the label is wrong (`"hit"` or `"no_hit"`);
2. deletes pairs that are too ambiguous to label, or adds new ones (any unique `id`);
3. sets `"reviewed": true` on each pair once checked.

The threshold sweep (step 6) refuses to print results while any pair has `"reviewed": false`.

**Rule of thumb for a label:** would one correct, cached answer to `a` also be a correct and
complete answer to `b`? If yes, `hit`. If a careful reader would call it wrong or missing
something, `no_hit`.

## Running the sweep

`sweep_threshold.py` embeds both prompts of every pair with the same embedding service the
gateway uses (Compose service `embeddings`, model `BAAI/bge-small-en-v1.5`), normalizes text
the same way the gateway does, and for each threshold from 0.70 to 0.99 reports:

- **hit rate:** share of `hit` pairs at or above the threshold (paraphrases served from cache);
- **false-hit rate:** share of `no_hit` pairs at or above it (wrong answers served).

It writes `results/threshold-sweep.md` (table, plus the pairs the best candidate thresholds
still get wrong), `threshold-sweep.csv`, `threshold-sweep.png` and `pair-similarities.csv`.

- **In GitHub Actions** (no Docker needed locally): Actions tab → *Cache threshold sweep* →
  *Run workflow*. The table appears as annotations on the run, and the files as an artifact.
- **With Docker:** `docker compose up -d embeddings`, then
  `python eval/sweep_threshold.py` (needs `pip install matplotlib` for the plot).

CI runs `--check-only` on every push: it embeds every pair through the real service to show
the pipeline works, but prints no rates. Tests: `python -m unittest discover eval`.

**Simplification:** each pair is scored on its own. In the gateway, a prompt is compared with
every cached prompt in its scope and the nearest one wins, so with many cached entries a
false hit can also come from a third prompt.
