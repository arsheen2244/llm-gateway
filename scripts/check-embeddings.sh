#!/usr/bin/env bash
# Checks the local embedding service (docker compose service "embeddings"): waits for it to
# load its model, then embeds two paraphrases and one unrelated sentence and prints the
# cosine similarities, so you can see the vectors carry meaning.
#
# Usage: scripts/check-embeddings.sh [url]     (default http://localhost:8082)
set -euo pipefail

URL="${1:-http://localhost:8082}"
DIMENSIONS=384

embed() {
  curl -s -f -X POST -H 'Content-Type: application/json' \
    -d "{\"inputs\": $(jq -Rn --arg t "$1" '$t'), \"normalize\": true}" "$URL/embed"
}

echo "Waiting for the embedding service at $URL (first start downloads the model) ..."
for _ in $(seq 1 90); do
  curl -s -f -o /dev/null "$URL/health" && break
  sleep 2
done
curl -s -f -o /dev/null "$URL/health" || { echo "FAIL: embedding service never became healthy"; exit 1; }

a=$(embed "How do I reverse a list in Python?")
b=$(embed "What's the way to reverse a Python list?")
c=$(embed "What is the capital of France?")

length=$(echo "$a" | jq '.[0] | length')
[ "$length" = "$DIMENSIONS" ] || { echo "FAIL: expected $DIMENSIONS numbers, got $length"; exit 1; }

# Vectors are unit length, so cosine similarity is the dot product.
dot() { jq -n --argjson x "$1" --argjson y "$2" '[range(0; $x[0] | length) as $i | $x[0][$i] * $y[0][$i]] | add'; }
echo "Vector length: $length"
echo "Similarity, paraphrases: $(dot "$a" "$b")"
echo "Similarity, unrelated:   $(dot "$a" "$c")"
echo "Embedding service OK."
