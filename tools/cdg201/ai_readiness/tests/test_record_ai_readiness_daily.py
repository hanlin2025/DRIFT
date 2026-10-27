import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import record_ai_readiness_daily as daily
from record_ai_readiness_daily import checkpoint_decision, visit_proxies, write_failed_daily_attempt
from run_ai_maturation_check import WORKFLOW_STAGES, run_workflow


class TestReadinessDecision(unittest.TestCase):
    def test_insufficient_labels_is_no_go(self):
        self.assertEqual(checkpoint_decision(29, 10, 3, True), "NO_GO_INSUFFICIENT_CONFIDENT_LABELS")

    def test_insufficient_vessel_diversity_is_no_go(self):
        self.assertEqual(checkpoint_decision(30, 9, 3, True), "NO_GO_INSUFFICIENT_CONFIDENT_LABELS")

    def test_labels_without_metrics_is_conditional(self):
        self.assertEqual(checkpoint_decision(30, 10, 3, False), "CONDITIONAL_GO_LABELS_READY_BASELINE_NOT_YET_AVAILABLE")

    def test_ready_baseline_remains_exploratory(self):
        self.assertEqual(checkpoint_decision(30, 10, 3, True), "CONDITIONAL_GO_BASELINE_AVAILABLE_EXPLORATORY_ONLY")

    def test_visit_proxy_keeps_distinct_location_context(self):
        rows = [{"eta_imo": "1234567", "eta_location_from": "SEA", "eta_location_to": "P1", "actual_time": "2026-10-27 12:00:00"},
                {"eta_imo": "1234567", "eta_location_from": "SEA", "eta_location_to": "P1", "actual_time": "2026-10-27 12:00:00"},
                {"eta_imo": "1234567", "eta_location_from": "SEA", "eta_location_to": "P2", "actual_time": "2026-10-27 12:00:00"}]
        self.assertEqual(len(visit_proxies(rows)), 2)

    def test_refreshes_revisions_before_candidates(self):
        seen = []
        def stage(script):
            seen.append(script)
            return {"script": script, "exit_code": 0, "stdout": "", "stderr": ""}
        self.assertEqual(run_workflow(stage), [
            {"script": script, "exit_code": 0, "stdout": "", "stderr": ""}
            for script in WORKFLOW_STAGES
        ])
        self.assertEqual(seen, list(WORKFLOW_STAGES))
        self.assertEqual(seen[0], "detect_mpa_expected_revisions.py")

    def test_failed_revision_refresh_stops_downstream_stages(self):
        seen = []
        def stage(script):
            seen.append(script)
            return {"script": script, "exit_code": 1 if script == "detect_mpa_expected_revisions.py" else 0,
                    "stdout": "", "stderr": ""}
        results = run_workflow(stage)
        self.assertEqual(len(results), 1)
        self.assertEqual(seen, ["detect_mpa_expected_revisions.py"])

    def test_legacy_trend_schema_starts_versioned_trend(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            original, versioned = daily.TREND, daily.TREND_V2
            try:
                daily.TREND, daily.TREND_V2 = root / "legacy.csv", root / "v2.csv"
                daily.TREND.write_text("old_column\nold_value\n")
                target = daily.append_trend({"new_column": "new_value"})
                self.assertEqual(target, daily.TREND_V2)
                self.assertEqual(daily.TREND.read_text(), "old_column\nold_value\n")
                self.assertIn("new_column", daily.TREND_V2.read_text())
            finally:
                daily.TREND, daily.TREND_V2 = original, versioned


if __name__ == "__main__":
    unittest.main()

