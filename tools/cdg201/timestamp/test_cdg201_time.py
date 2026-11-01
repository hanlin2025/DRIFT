import unittest
from datetime import timezone

from cdg201_time import mpa_representations, nea_sgt, parse_mpa_source, parse_retrieval_utc


class Cdg201TimeTests(unittest.TestCase):
    def test_naive_mpa_midnight_is_sgt_and_previous_utc_day(self):
        value = parse_mpa_source("2026-10-27 00:15:00")
        self.assertEqual(value.isoformat(), "2026-10-27T00:15:00+08:00")
        self.assertEqual(value.astimezone(timezone.utc).isoformat(), "2026-10-26T16:15:00+00:00")

    def test_offset_aware_mpa_source_preserves_instant(self):
        self.assertEqual(parse_mpa_source("2026-10-27T00:15:00+08:00").isoformat(), "2026-10-27T00:15:00+08:00")

    def test_nea_offset_aware_value_is_not_double_converted(self):
        self.assertEqual(nea_sgt("2026-10-27T00:15:00+08:00"), "2026-10-27T00:15:00+08:00")
        self.assertEqual(nea_sgt("2026-10-26T16:15:00+00:00"), "2026-10-27T00:15:00+08:00")

    def test_retrieval_time_stays_utc(self):
        self.assertEqual(parse_retrieval_utc("2026-10-26T16:15:00+00:00").isoformat(), "2026-10-26T16:15:00+00:00")
        self.assertEqual(mpa_representations("2026-10-27 00:15:00")["raw"], "2026-10-27 00:15:00")
