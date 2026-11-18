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

The labels are a **draft written by Claude**. Before any result is reported, the owner reads
every pair and:

1. fixes `expected` wherever the label is wrong (`"hit"` or `"no_hit"`);
2. deletes pairs that are too ambiguous to label, or adds new ones (any unique `id`);
3. sets `"reviewed": true` on each pair once checked.

The threshold sweep (step 6) refuses to print results while any pair has `"reviewed": false`.

**Rule of thumb for a label:** would one correct, cached answer to `a` also be a correct and
complete answer to `b`? If yes, `hit`. If a careful reader would call it wrong or missing
something, `no_hit`.
