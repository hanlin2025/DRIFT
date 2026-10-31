import unittest
from unittest.mock import patch
from pathlib import Path
from tempfile import TemporaryDirectory

import record_ai_readiness_daily as daily
from record_ai_readiness_daily import checkpoint_decision, visit_proxies, write_failed_daily_attempt
from run_ai_maturation_check import WORKFLOW_STAGES, run, run_workflow


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

    def test_runs_curated_stages_in_dependency_order(self):
        seen = []
        def stage(script):
            seen.append(script)
            return {"script": script, "exit_code": 0, "stdout": "", "stderr": ""}
        self.assertEqual(run_workflow(stage), [
            {"script": script, "exit_code": 0, "stdout": "", "stderr": ""}
            for script in WORKFLOW_STAGES
        ])
        self.assertEqual(seen, list(WORKFLOW_STAGES))
        self.assertEqual(seen[0], "build_eta_ground_truth_candidates.py")

    def test_failed_first_stage_stops_downstream_stages(self):
        seen = []
        def stage(script):
            seen.append(script)
            return {"script": script, "exit_code": 1 if script == "build_eta_ground_truth_candidates.py" else 0,
                    "stdout": "", "stderr": ""}
        results = run_workflow(stage)
        self.assertEqual(len(results), 1)
        self.assertEqual(seen, ["build_eta_ground_truth_candidates.py"])

    def test_missing_curated_stage_becomes_a_failure_result(self):
        import run_ai_maturation_check as workflow
        original = workflow.CONTEXTUAL_TOOLS
        try:
            workflow.CONTEXTUAL_TOOLS = Path("/definitely-missing")
            result = run("build_eta_ground_truth_candidates.py")
            self.assertEqual(result["exit_code"], 1)
            self.assertIn("Required curated stage is missing", result["stderr"])
        finally:
            workflow.CONTEXTUAL_TOOLS = original

    def test_weather_join_receives_its_required_curated_arguments(self):
        import run_ai_maturation_check as workflow
        class Completed:
            returncode = 0
            stdout = "ok"
            stderr = ""
        with patch.object(workflow, "subprocess") as process:
            process.run.return_value = Completed()
            result = workflow.run("build_mpa_nea_inspection_join.py")
        self.assertEqual(result["exit_code"], 0)
        command = process.run.call_args.args[0]
        self.assertIn("--revisions", command)
        self.assertIn("--weather-root", command)
        self.assertIn("--output", command)
        self.assertIn(str(workflow.CONTEXTUAL_TOOLS / "build_mpa_nea_inspection_join.py"), command)

    def test_daily_snapshot_invokes_the_curated_workflow_file(self):
        class Completed:
            returncode = 0
            stdout = "ok"
            stderr = ""
        with patch.object(daily, "subprocess") as process:
            process.run.return_value = Completed()
            result = daily.run_existing()
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(process.run.call_args.args[0][1], str(daily.MATURATION_CHECK))

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

