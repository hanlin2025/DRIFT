import unittest
from build_eta_ground_truth_candidates import classify_candidate


ETA = {"imo":"1234567","latest_collection_utc":"2026-10-27T00:00:00+00:00",
       "source_timestamp_utc_latest_bound":"2026-10-28T00:00:00+00:00","revised_expected":"2026-10-27 12:00:00",
       "location_from":"SEA","location_to":"P1","call_sign":"CALL"}


def actual(**overrides):
    row = {"imo":"1234567","call_sign":"CALL","arrived_time":"2026-10-27 12:30:00",
           "location_from":"SEA","location_to":"P1","collection_utc":"2026-10-28T01:00:00+00:00","queried_date":"2026-10-27"}
    row.update(overrides); return row


class TestMaturation(unittest.TestCase):
    def test_single_context_match(self):
        status, chosen, *_ = classify_candidate(ETA,[actual()])
        self.assertEqual(status,"matched"); self.assertEqual(chosen["imo"],"1234567")

    def test_multiple_visits_are_ambiguous(self):
        status, _, _, near, _, reason = classify_candidate(ETA,[actual(),actual(arrived_time="2026-10-27 13:00:00")])
        self.assertEqual(status,"ambiguous"); self.assertEqual(near,2); self.assertEqual(reason,"multiple_possible_visits")

    def test_absence_remains_pending(self):
        status, _, _, _, closed, _ = classify_candidate(ETA,[])
        self.assertEqual(status,"pending"); self.assertFalse(closed)

    def test_invalid_identity_is_invalid_not_negative(self):
        eta={**ETA,"imo":"0"}
        status, *_ = classify_candidate(eta,[actual()])
        self.assertEqual(status,"invalid")


if __name__ == "__main__":
    unittest.main()
