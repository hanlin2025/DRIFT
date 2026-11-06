import unittest

from audit_matured_eta_outcomes import chronology_valid, proxy


class TestMaturedOutcomeAudit(unittest.TestCase):
    def test_proxy_keeps_location_context_and_reported_timestamp(self):
        row = {"eta_imo": "1234567", "eta_location_from": "SEA", "eta_location_to": "P1", "actual_time": "2026-10-27 12:30:00"}
        self.assertEqual(proxy(row), ("1234567", "SEA", "P1", "2026-10-27 12:30:00"))

    def test_chronology_requires_outcome_seen_after_cutoff(self):
        row = {"eta_collection_utc": "2026-10-27T00:00:00+00:00", "actual_collection_utc": "2026-10-27T03:00:00+00:00", "actual_time": "2026-10-27 09:00:00"}
        self.assertTrue(chronology_valid(row))
        self.assertFalse(chronology_valid({**row, "actual_collection_utc": "2026-10-26T23:00:00+00:00"}))


if __name__ == "__main__":
    unittest.main()

