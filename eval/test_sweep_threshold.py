"""Tests for the threshold sweep's logic (no embedding service needed).

Run from the repo root: python -m unittest discover eval
"""

import json
import tempfile
import unittest
from pathlib import Path

import sweep_threshold as sweep


def pair(id_, expected, reviewed=True):
    return sweep.Pair(id_, expected, "c", "a " + id_, "b " + id_, reviewed)


class NormalizeTest(unittest.TestCase):

    def test_matches_the_gateway(self):
        self.assertEqual(sweep.normalize("  What  is\tRedis?\n"), "What is Redis?")
        self.assertEqual(sweep.normalize("Café"), "Café")  # NFC
        self.assertEqual(sweep.normalize("Case Kept"), "Case Kept")


class SweepTest(unittest.TestCase):

    def setUp(self):
        self.pairs = [pair("h1", "hit"), pair("h2", "hit"), pair("n1", "no_hit"), pair("n2", "no_hit")]
        self.similarity = {"h1": 0.97, "h2": 0.88, "n1": 0.91, "n2": 0.60}

    def test_rates_count_pairs_at_or_above_the_threshold(self):
        rows = sweep.sweep(self.pairs, self.similarity, [0.85, 0.90, 0.95])

        self.assertEqual([(r.hits, r.false_hits) for r in rows], [(2, 1), (1, 1), (1, 0)])
        self.assertEqual(rows[0].hit_rate, 1.0)
        self.assertEqual(rows[0].false_hit_rate, 0.5)

    def test_threshold_is_inclusive(self):
        rows = sweep.sweep(self.pairs, self.similarity, [0.97])
        self.assertEqual(rows[0].hits, 1)

    def test_wrong_at_lists_false_hits_and_missed_paraphrases(self):
        false_hits, missed = sweep.wrong_at(self.pairs, self.similarity, 0.90)

        self.assertEqual([p.id for p in false_hits], ["n1"])
        self.assertEqual([p.id for p in missed], ["h2"])

    def test_candidates_pick_the_best_hit_rate_within_the_false_hit_limit(self):
        rows = sweep.sweep(self.pairs, self.similarity, [0.85, 0.90, 0.95])

        picks = sweep.candidates(rows)

        self.assertEqual(picks["zero false hits"].threshold, 0.95)

    def test_dot_product_of_unit_vectors_is_cosine_similarity(self):
        self.assertAlmostEqual(sweep.dot([0.6, 0.8], [0.6, 0.8]), 1.0)
        self.assertAlmostEqual(sweep.dot([1.0, 0.0], [0.0, 1.0]), 0.0)


class LoadPairsTest(unittest.TestCase):

    def write(self, pairs):
        f = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
        json.dump({"pairs": pairs}, f)
        f.close()
        self.addCleanup(Path(f.name).unlink)
        return Path(f.name)

    def test_unreviewed_pairs_are_reported(self):
        path = self.write([
            {"id": "a", "expected": "hit", "category": "c", "a": "x", "b": "y", "reviewed": True},
            {"id": "b", "expected": "no_hit", "category": "c", "a": "x", "b": "z", "reviewed": False},
        ])
        self.assertEqual(sweep.unreviewed(sweep.load_pairs(path)), ["b"])

    def test_refuses_to_report_results_while_pairs_are_unreviewed(self):
        path = self.write([{"id": "a", "expected": "hit", "category": "c", "a": "x", "b": "y", "reviewed": False}])
        self.assertEqual(sweep.main(["--pairs", str(path)]), 2)

    def test_rejects_unknown_labels_and_duplicate_ids(self):
        with self.assertRaises(ValueError):
            sweep.load_pairs(self.write([{"id": "a", "expected": "maybe", "category": "c", "a": "x", "b": "y"}]))
        dup = {"id": "a", "expected": "hit", "category": "c", "a": "x", "b": "y"}
        with self.assertRaises(ValueError):
            sweep.load_pairs(self.write([dup, dup]))

    def test_the_real_pairs_file_is_valid(self):
        pairs = sweep.load_pairs(sweep.PAIRS_FILE)
        self.assertGreaterEqual(len(pairs), 150)


if __name__ == "__main__":
    unittest.main()
