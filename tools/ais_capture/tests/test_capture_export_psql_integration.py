"""Opt-in regression against a disposable local PostgreSQL server and real psql client."""

from __future__ import annotations

import os
import shutil
import subprocess
import unittest
import uuid
from datetime import datetime, timezone
from urllib.parse import unquote, urlparse
from unittest.mock import patch

from tools.ais_capture import capture_export as capture


ADMIN_URL_ENV = "DRIFT_AIS_CAPTURE_TEST_ADMIN_URL"
DISPOSABLE_ENV = "DRIFT_AIS_CAPTURE_TEST_DISPOSABLE"
TARGET_MMSI = "990285009"
OTHER_MMSI = "990285010"
VESSEL_NAME = "CDG285 TEST ONLY CONTAINER"
START = datetime(2026, 11, 1, 0, 0, tzinfo=timezone.utc)
END = datetime(2026, 11, 1, 0, 30, tzinfo=timezone.utc)


class CaptureExportPsqlIntegrationTests(unittest.TestCase):
    """Only run when explicitly pointed at a disposable, non-default local PG instance."""

    @classmethod
    def setUpClass(cls):
        admin_url = os.environ.get(ADMIN_URL_ENV)
        disposable = os.environ.get(DISPOSABLE_ENV)
        if not admin_url and not disposable:
            raise unittest.SkipTest(f"set {ADMIN_URL_ENV} and {DISPOSABLE_ENV}=YES to enable the isolated PostgreSQL regression")
        if disposable != "YES" or not admin_url:
            raise RuntimeError(f"the PostgreSQL regression requires {DISPOSABLE_ENV}=YES and an explicit admin URL")

        parsed = urlparse(admin_url)
        try:
            port = parsed.port
        except ValueError as exc:
            raise RuntimeError("the PostgreSQL test admin URL has an invalid port") from exc
        if (parsed.scheme not in ("postgres", "postgresql")
                or parsed.hostname not in ("127.0.0.1", "localhost", "::1")
                or port is None or port == 5432
                or parsed.path != "/postgres"
                or not parsed.username
                or parsed.query or parsed.fragment):
            raise RuntimeError(
                "the PostgreSQL regression accepts only an explicit loopback URL on a non-default port to database postgres"
            )

        cls.psql = shutil.which("psql")
        if cls.psql is None:
            raise RuntimeError("the PostgreSQL regression requires a real psql client on PATH")
        cls.owner = unquote(parsed.username)
        cls.connection_env = {
            "PGHOST": parsed.hostname,
            "PGPORT": str(port),
            "PGUSER": cls.owner,
            "PGPASSWORD": unquote(parsed.password or ""),
            "PGSSLMODE": "disable",
            "PATH": os.environ.get("PATH", ""),
            "HOME": os.environ.get("HOME", ""),
            "TMPDIR": os.environ.get("TMPDIR", "/tmp"),
            "LANG": os.environ.get("LANG", "C.UTF-8"),
            "LC_ALL": os.environ.get("LC_ALL", "C.UTF-8"),
        }
        cls.database = f"drift_ais_capture_test_{uuid.uuid4().hex}"

        # Probe the exact host, port, database, and authenticated owner before any database write.
        probe = cls._query_database(
            "postgres", "SELECT current_database() || '|' || current_user"
        )
        if probe.returncode != 0 or probe.stdout.strip() != f"postgres|{cls.owner}":
            raise RuntimeError("the disposable PostgreSQL destination could not be verified; no test database was created")

        created = cls._run_script("postgres", f"CREATE DATABASE {cls.database};\n")
        if created.returncode != 0:
            raise RuntimeError("the unique PostgreSQL test database could not be created")
        cls.addClassCleanup(cls._drop_database)

        ownership = cls._query_database(
            cls.database, "SELECT current_database() || '|' || current_user"
        )
        if ownership.returncode != 0 or ownership.stdout.strip() != f"{cls.database}|{cls.owner}":
            raise RuntimeError("the unique PostgreSQL test database owner could not be verified")

    @classmethod
    def _run_script(cls, database: str, script: str) -> subprocess.CompletedProcess[str]:
        environment = {**cls.connection_env, "PGDATABASE": database}
        return subprocess.run(
            [cls.psql, "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", database, "-f", "-"],
            input=script,
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=False,
            env=environment,
        )

    @classmethod
    def _query_database(cls, database: str, sql: str) -> subprocess.CompletedProcess[str]:
        environment = {**cls.connection_env, "PGDATABASE": database}
        return subprocess.run(
            [cls.psql, "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-d", database, "-c", sql],
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=False,
            env=environment,
        )

    @classmethod
    def _drop_database(cls) -> None:
        dropped = cls._run_script("postgres", f"DROP DATABASE IF EXISTS {cls.database};\n")
        if dropped.returncode != 0:
            raise RuntimeError("the unique PostgreSQL test database could not be removed")

    def test_real_psql_substitutes_all_bounds_and_preserves_the_export_contract(self):
        setup = f"""
CREATE TABLE vessel_observations (
    id BIGINT PRIMARY KEY,
    mmsi VARCHAR(9) NOT NULL,
    vessel_name TEXT,
    latitude NUMERIC(9, 4) NOT NULL,
    longitude NUMERIC(9, 4) NOT NULL,
    speed_over_ground_knots NUMERIC(5, 1),
    course_over_ground_degrees NUMERIC(5, 1),
    true_heading_degrees INTEGER,
    navigational_status INTEGER,
    position_valid BOOLEAN,
    ais_utc_second INTEGER,
    ingested_at TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL
);

INSERT INTO vessel_observations VALUES
    (8, '{TARGET_MMSI}', '{VESSEL_NAME}', 1.2300, 103.8300, 8.5, 90.0, 90, 0, TRUE, 30,
        '2026-11-01T00:30:00Z', 'AIS_STREAM'),
    (40, '{TARGET_MMSI}', NULL, 1.2400, 103.8400, NULL, NULL, NULL, NULL, NULL, NULL,
        '2026-11-01T00:10:00Z', 'AIS_STREAM'),
    (15, '{TARGET_MMSI}', '{VESSEL_NAME}', 1.2350, 103.8350, 8.2, 89.0, 89, 0, TRUE, 10,
        '2026-11-01T00:10:00Z', 'AIS_STREAM'),
    (3, '{TARGET_MMSI}', '{VESSEL_NAME}', 1.2200, 103.8200, 8.0, 88.0, 88, 0, TRUE, 0,
        '2026-11-01T00:00:00Z', 'AIS_STREAM'),
    (2, '{TARGET_MMSI}', '{VESSEL_NAME}', 7.0, 100.0, 7.0, 70.0, 70, 0, TRUE, 59,
        '2026-10-31T23:59:59.999999Z', 'AIS_STREAM'),
    (9, '{TARGET_MMSI}', '{VESSEL_NAME}', 7.1, 100.1, 7.1, 71.0, 71, 0, TRUE, 1,
        '2026-11-01T00:30:00.000001Z', 'AIS_STREAM'),
    (10, '{OTHER_MMSI}', '{VESSEL_NAME}', 1.2500, 103.8500, 8.1, 87.0, 87, 0, TRUE, 15,
        '2026-11-01T00:15:00Z', 'AIS_STREAM'),
    (11, '{TARGET_MMSI}', '{VESSEL_NAME}', 1.2600, 103.8600, 8.3, 86.0, 86, 0, TRUE, 20,
        '2026-11-01T00:15:00Z', 'SEEDED');
"""
        setup_result = self._run_script(self.database, setup)
        self.assertEqual(setup_result.returncode, 0, "synthetic setup failed inside the uniquely owned test database")

        environment = {**self.connection_env, "PGDATABASE": self.database}
        with patch.dict(os.environ, environment, clear=True):
            csv_text = capture.query_rows(TARGET_MMSI, START, END)

        rows = capture.read_rows(csv_text)
        capture.validate_export_rows(rows, TARGET_MMSI, START, END)
        self.assertEqual(csv_text.splitlines()[0].split(","), list(capture.CSV_FIELDS))
        self.assertEqual([row["observation_id"] for row in rows], ["3", "15", "40", "8"])
        self.assertTrue(all(row["mmsi"] == TARGET_MMSI for row in rows))
        self.assertTrue(all(row["source"] == "AIS_STREAM" for row in rows))
        self.assertEqual(rows[0]["ingested_at_utc"], "2026-11-01T00:00:00.000000Z")
        self.assertEqual(rows[-1]["ingested_at_utc"], "2026-11-01T00:30:00.000000Z")
        self.assertEqual(
            [rows[2][field] for field in (
                "vessel_name", "speed_over_ground_knots", "course_over_ground_degrees",
                "true_heading_degrees", "navigational_status", "position_valid", "ais_utc_second",
            )],
            [""] * 7,
        )
        quality = capture.summarize(rows, VESSEL_NAME)
        self.assertEqual(quality["observation_count"], 4)
        self.assertEqual(quality["position_valid_unknown_count"], 1)
        self.assertEqual(quality["missing_speed_count"], 1)

        dropped = self._run_script(self.database, "DROP TABLE vessel_observations;\n")
        self.assertEqual(dropped.returncode, 0, "synthetic table cleanup failed inside the disposable database")
        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaisesRegex(capture.CaptureError, "database export failed"):
                capture.query_rows(TARGET_MMSI, START, END)


if __name__ == "__main__":
    unittest.main()
