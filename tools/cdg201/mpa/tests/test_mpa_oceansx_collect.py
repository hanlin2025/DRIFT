import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

import mpa_oceansx_collect as collector


class TestResponseClassification(unittest.TestCase):
    def setUp(self):
        self.old_get, self.old_argv = collector.get, sys.argv[:]
        self.old_env = {key: os.environ.get(key) for key in ("MPA_OCEANSX_API_KEY", "MPA_OCEANSX_API_BASE", "MPA_DUE_TO_ARRIVE_PATH", "MPA_DUE_TO_DEPART_PATH", "MPA_REQUEST_RETRIES", "MPA_REQUEST_PAUSE_SECONDS")}
        os.environ.update({"MPA_OCEANSX_API_KEY":"test", "MPA_OCEANSX_API_BASE":"https://example.invalid", "MPA_DUE_TO_ARRIVE_PATH":"/arrive/{date}"})
        os.environ.update({"MPA_REQUEST_RETRIES":"2", "MPA_REQUEST_PAUSE_SECONDS":"0"})
        os.environ.pop("MPA_DUE_TO_DEPART_PATH", None)

    def tearDown(self):
        collector.get, sys.argv = self.old_get, self.old_argv
        for key, value in self.old_env.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value

    def run_once(self, response):
        collector.get = lambda *args: response
        with tempfile.TemporaryDirectory() as directory:
            sys.argv = ["collector", "--start", "2026-10-26", "--days", "1", "--feed-group", "expected", "--output", directory]
            collector.main()
            return json.loads(next(Path(directory).glob("*.manifest.json")).read_text())

    def test_http_204_no_body_is_missing_coverage(self):
        manifest = self.run_once((b"", 204))
        self.assertEqual(manifest["error"], "HTTPNoContent")
        self.assertFalse(manifest["response_body_present"])
        self.assertFalse(manifest["schema_valid"])

    def test_valid_empty_array_is_successful_zero_coverage(self):
        manifest = self.run_once((b"[]", 200))
        self.assertEqual(manifest["error"], "")
        self.assertTrue(manifest["schema_valid"])
        self.assertEqual(manifest["record_count"], 0)

    def test_malformed_json_is_not_empty_array(self):
        manifest = self.run_once((b"not-json", 200))
        self.assertEqual(manifest["error"], "MalformedJSON")
        self.assertTrue(manifest["response_body_present"])

    def test_transient_server_error_is_retried_before_recording_success(self):
        responses = [(b"temporary", 502), (b"[]", 200)]
        collector.get = lambda *args: responses.pop(0)
        with tempfile.TemporaryDirectory() as directory:
            sys.argv = ["collector", "--start", "2026-10-26", "--days", "1", "--feed-group", "expected", "--output", directory]
            collector.main()
            manifest = json.loads(next(Path(directory).glob("*.manifest.json")).read_text())
        self.assertEqual(manifest["retry_count"], 1)
        self.assertEqual(manifest["http_status"], 200)
        self.assertTrue(manifest["schema_valid"])


if __name__ == "__main__":
    unittest.main()
