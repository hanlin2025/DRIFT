import csv
import contextlib
import io
import json
import os
import subprocess
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from tools.ais_capture import capture_export as capture


REPO = Path(__file__).resolve().parents[3]


def row(stamp, *, name="MV TEST CONTAINER", valid="true", speed="10.0", ais_second="12"):
    return {
        "observation_id": "1",
        "mmsi": "563123456",
        "vessel_name": name,
        "latitude": "1.26",
        "longitude": "103.84",
        "speed_over_ground_knots": speed,
        "course_over_ground_degrees": "90.0",
        "true_heading_degrees": "90",
        "navigational_status": "0",
        "position_valid": valid,
        "ais_utc_second": ais_second,
        "ingested_at_utc": stamp,
        "source": "AIS_STREAM",
    }


class CaptureExportTests(unittest.TestCase):
    def test_imo_checksum_and_mmsi_format_are_checked(self):
        self.assertTrue(capture.valid_imo("1234567"))
        self.assertFalse(capture.valid_imo("1234568"))
        self.assertTrue(capture.valid_mmsi("563123456"))
        self.assertFalse(capture.valid_mmsi("063123456"))
        self.assertFalse(capture.valid_mmsi("56312345"))

    def test_capture_timestamps_require_offsets_and_window_is_bounded(self):
        parsed = capture.parse_timestamp("2026-11-02T10:00:00+08:00", "--from")
        self.assertEqual(parsed, datetime(2026, 11, 2, 2, tzinfo=timezone.utc))
        with self.assertRaisesRegex(capture.CaptureError, "timezone"):
            capture.parse_timestamp("2026-11-02T10:00:00", "--from")

        class Args:
            mmsi = "563123456"
            imo = "1234567"
            vessel_name = "MV Test Container"
            vessel_type = "container"
            identity_reference = "registry record ref"
            terms_reference = "provider terms review ref"
            start = "2026-11-01T00:00:00Z"
            end = "2026-11-04T00:01:00Z"
            identity_checked_at = "2026-10-31T00:00:00Z"
            terms_reviewed_at = "2026-10-31T00:00:00Z"
            retain_until = "2026-12-01"

        with self.assertRaisesRegex(capture.CaptureError, "72 hours"):
            capture.validate_selection(Args())

    def test_quality_summary_keeps_bad_and_missing_values_visible(self):
        rows = [
            row("2026-11-02T02:00:00.000000Z"),
            row("2026-11-02T02:15:00.000000Z", name="UNEXPECTED NAME", valid="false", speed="", ais_second=""),
            row("2026-11-02T02:20:00.000000Z", name="UNEXPECTED NAME"),
        ]
        quality = capture.summarize(rows, "MV Test Container")
        self.assertEqual(quality["observation_count"], 3)
        self.assertEqual(quality["maximum_report_gap_seconds"], 900.0)
        self.assertEqual(quality["valid_position_count"], 2)
        self.assertEqual(quality["maximum_valid_position_gap_seconds"], 1200.0)
        self.assertEqual(quality["position_valid_false_count"], 1)
        self.assertEqual(quality["missing_speed_count"], 1)
        self.assertEqual(quality["missing_ais_utc_second_count"], 1)
        self.assertEqual(quality["reported_name_mismatch_count"], 2)
        self.assertIn("GAP_OVER_10_MINUTES", quality["automated_quality_flags"])
        self.assertEqual(quality["operator_acceptance"], "PENDING")

    def test_empty_capture_is_reported_without_claiming_coverage(self):
        quality = capture.summarize([], "MV Test Container")
        self.assertEqual(quality["automated_quality_status"], "NO_REPORTS")
        self.assertIsNone(quality["first_ingested_at_utc"])
        self.assertIn("NO_OBSERVATIONS", quality["automated_quality_flags"])

    def test_sql_is_bounded_by_exact_mmsi_and_existing_live_source(self):
        self.assertIn("mmsi = :'mmsi'", capture.COPY_SQL)
        self.assertIn("source = 'AIS_STREAM'", capture.COPY_SQL)
        self.assertIn("ingested_at >= :'from_utc'::timestamptz", capture.COPY_SQL)
        self.assertIn("ingested_at <= :'to_utc'::timestamptz", capture.COPY_SQL)
        self.assertIn("ORDER BY ingested_at, id", capture.COPY_SQL)

    def test_export_rows_cannot_mix_seeded_sources_or_other_vessels(self):
        start = datetime(2026, 11, 2, tzinfo=timezone.utc)
        end = datetime(2026, 11, 2, 0, 30, tzinfo=timezone.utc)
        valid = row("2026-11-02T00:10:00Z")
        capture.validate_export_rows([valid], "563123456", start, end)
        with self.assertRaisesRegex(capture.CaptureError, "MMSI/source"):
            capture.validate_export_rows([{**valid, "source": "SEEDED"}], "563123456", start, end)
        with self.assertRaisesRegex(capture.CaptureError, "MMSI/source"):
            capture.validate_export_rows([{**valid, "mmsi": "563123457"}], "563123456", start, end)
        with self.assertRaisesRegex(capture.CaptureError, "time window"):
            capture.validate_export_rows([row("2026-11-02T00:31:00Z")], "563123456", start, end)

    def test_missing_postgres_client_has_a_safe_offline_fallback(self):
        with patch.object(capture.shutil, "which", return_value=None):
            with self.assertRaisesRegex(capture.CaptureError, "offline fixture"):
                capture.query_rows("563123456", datetime.now(timezone.utc), datetime.now(timezone.utc))

    def test_provider_key_is_not_passed_to_postgres_client(self):
        start = datetime(2026, 11, 2, tzinfo=timezone.utc)
        end = datetime(2026, 11, 2, 0, 30, tzinfo=timezone.utc)
        completed = subprocess.CompletedProcess([], 0, ",".join(capture.CSV_FIELDS) + "\n", "")
        with patch.dict(os.environ, {"AISSTREAM_API_KEY": "fixture-secret-must-not-leak"}):
            with patch.object(capture.shutil, "which", return_value="/usr/bin/psql"):
                with patch.object(capture.subprocess, "run", return_value=completed) as run:
                    capture.query_rows("563123456", start, end)
        child_environment = run.call_args.kwargs["env"]
        self.assertNotIn("AISSTREAM_API_KEY", child_environment)

    def test_rehearsal_fixture_is_explicitly_fictional_and_seeded(self):
        fixture = REPO / "fixtures/cdg285/fictional_ais_track.csv"
        with fixture.open(encoding="utf-8", newline="") as handle:
            records = list(csv.DictReader(handle))
        self.assertGreaterEqual(len(records), 4)
        self.assertEqual({record["source"] for record in records}, {"SEEDED"})
        self.assertEqual({record["mmsi"] for record in records}, {"990285001", "990285002"})
        self.assertTrue(all(record["vessel_name"].startswith("CDG285 FICTIONAL") for record in records))

    def test_empty_export_writes_manifest_and_returns_distinct_status(self):
        today = datetime.now(timezone.utc).date().isoformat()
        args = [
            "--role", "mother", "--vessel-type", "container", "--vessel-name", "MV Test Container",
            "--imo", "1234567", "--mmsi", "563123456", "--identity-reference", "registry ref",
            "--identity-checked-at", "2026-11-01T00:00:00Z", "--from", "2026-11-02T00:00:00Z",
            "--to", "2026-11-02T00:30:00Z", "--terms-reference", "terms ref",
            "--terms-reviewed-at", "2026-11-01T00:00:00Z", "--replay-permission", "UNKNOWN",
            "--retain-until", today,
        ]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "mother.csv"
            args.extend(["--output", str(output)])
            with patch.object(capture, "query_rows", return_value=",".join(capture.CSV_FIELDS) + "\n"):
                with contextlib.redirect_stdout(io.StringIO()):
                    status = capture.main(args)
            self.assertEqual(status, 3)
            manifest_path = Path(directory) / "mother.manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest["classification"], "AUTHENTIC_PROVIDER_CAPTURE")
            self.assertEqual(manifest["data_quality"]["automated_quality_status"], "NO_REPORTS")
            self.assertEqual(manifest["data_quality"]["operator_acceptance"], "PENDING")
            self.assertEqual(manifest["provider_usage_review"]["replay_permission"], "UNKNOWN")
            self.assertNotIn("APIKey", manifest)

            accepted_output = Path(directory) / "mother-reviewed.csv"
            accepted_args = args[:-2] + ["--operator-acceptance", "ACCEPTED", "--output", str(accepted_output)]
            with patch.object(capture, "query_rows", return_value=",".join(capture.CSV_FIELDS) + "\n"):
                with contextlib.redirect_stdout(io.StringIO()):
                    accepted_status = capture.main(accepted_args)
            self.assertEqual(accepted_status, 3)
            accepted_manifest = json.loads((Path(directory) / "mother-reviewed.manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(accepted_manifest["data_quality"]["operator_acceptance"], "ACCEPTED")


if __name__ == "__main__":
    unittest.main()
