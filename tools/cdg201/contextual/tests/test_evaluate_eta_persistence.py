import unittest
from evaluate_eta_persistence import make_evaluation_rows, metrics, weather_is_safe


def label(**changes):
    row = {"label_status":"matched", "eta_imo":"1234567", "vessel_name":"Test", "eta_collection_utc":"2026-10-27T00:00:00+00:00",
           "eta_run_id":"run-1", "eta_revised_source_time":"2026-10-27 12:00:00", "actual_time":"2026-10-27 12:30:00",
           "eta_manifest":"eta.manifest.json", "actual_manifest":"actual.manifest.json"}
    row.update(changes); return row


class TestPersistenceEvaluation(unittest.TestCase):
    def test_valid_label_produces_signed_and_absolute_error(self):
        rows = make_evaluation_rows([label()], {})
        self.assertEqual(rows[0]["signed_error_minutes"], 30)
        self.assertEqual(metrics(rows)["mae_minutes"], 30)

    def test_censored_and_ambiguous_labels_are_excluded(self):
        rows = make_evaluation_rows([label(label_status="pending"), label(label_status="ambiguous")], {})
        self.assertEqual(rows, []); self.assertFalse(metrics(rows)["available"])

    def test_weather_after_cutoff_is_not_safe(self):
        weather = {("1234567", "2026-10-27T00:00:00+00:00", "run-1"): {"weather_join_status":"no_leakage_safe_live_weather_before_cutoff"}}
        rows = make_evaluation_rows([label()], weather)
        self.assertFalse(rows[0]["weather_feature_eligible"])
        self.assertFalse(weather_is_safe(weather[("1234567", "2026-10-27T00:00:00+00:00", "run-1")], "cutoff"))

    def test_repeated_visit_labels_do_not_bypass_match_gate(self):
        rows = make_evaluation_rows([label(label_status="ambiguous")], {})
        self.assertEqual(rows, [])


if __name__ == "__main__":
    unittest.main()
