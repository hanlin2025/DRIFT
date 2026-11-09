#!/usr/bin/env python3
"""Export one bounded, MMSI-scoped AISStream track from DRIFT's existing database."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import uuid
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from statistics import median

MAX_WINDOW = timedelta(hours=72)
GAP_REVIEW_SECONDS = 600
CSV_FIELDS = (
    "observation_id",
    "mmsi",
    "vessel_name",
    "latitude",
    "longitude",
    "speed_over_ground_knots",
    "course_over_ground_degrees",
    "true_heading_degrees",
    "navigational_status",
    "position_valid",
    "ais_utc_second",
    "ingested_at_utc",
    "source",
)

COPY_SQL = """COPY (
    SELECT id AS observation_id, mmsi, vessel_name, latitude, longitude,
           speed_over_ground_knots, course_over_ground_degrees, true_heading_degrees,
           navigational_status, position_valid, ais_utc_second,
           to_char(ingested_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') AS ingested_at_utc,
           source
    FROM vessel_observations
    WHERE mmsi = :'mmsi'
      AND source = 'AIS_STREAM'
      AND ingested_at >= :'from_utc'::timestamptz
      AND ingested_at <= :'to_utc'::timestamptz
    ORDER BY ingested_at, id
) TO STDOUT WITH (FORMAT csv, HEADER true);"""


class CaptureError(ValueError):
    """An invalid selection, export window, or capture result."""


def valid_mmsi(value: str) -> bool:
    return bool(re.fullmatch(r"[1-9][0-9]{8}", value))


def valid_imo(value: str) -> bool:
    if not re.fullmatch(r"[0-9]{7}", value):
        return False
    checksum = sum(int(digit) * weight for digit, weight in zip(value[:6], range(7, 1, -1)))
    return checksum % 10 == int(value[6])


def parse_timestamp(value: str, field: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise CaptureError(f"{field} must be an ISO-8601 timestamp with a UTC offset.") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise CaptureError(f"{field} must include a timezone offset.")
    return parsed.astimezone(timezone.utc)


def normalize_name(value: str | None) -> str | None:
    if not value or not value.strip():
        return None
    normalized = " ".join(value.upper().split())
    return re.sub(r"^(?:M/V|MV)\s+", "", normalized)


def validate_selection(args: argparse.Namespace) -> tuple[datetime, datetime, datetime, date]:
    if not valid_mmsi(args.mmsi):
        raise CaptureError("MMSI must contain nine digits and cannot start with zero.")
    if not valid_imo(args.imo):
        raise CaptureError("IMO must be seven digits with a valid check digit.")
    if not args.vessel_name.strip():
        raise CaptureError("Vessel name is required.")
    if not args.vessel_type.strip():
        raise CaptureError("Vessel type is required.")
    if not args.identity_reference.strip():
        raise CaptureError("An identity verification reference is required.")
    if not args.terms_reference.strip():
        raise CaptureError("A provider terms / usage review reference is required.")
    try:
        retention_until = date.fromisoformat(args.retain_until)
    except ValueError as exc:
        raise CaptureError("--retain-until must be an ISO-8601 date (YYYY-MM-DD).") from exc

    start = parse_timestamp(args.start, "--from")
    end = parse_timestamp(args.end, "--to")
    identity_checked_at = parse_timestamp(args.identity_checked_at, "--identity-checked-at")
    terms_reviewed_at = parse_timestamp(args.terms_reviewed_at, "--terms-reviewed-at")
    if start >= end:
        raise CaptureError("Capture window start must be before its end.")
    if end - start > MAX_WINDOW:
        raise CaptureError("Capture window cannot exceed 72 hours.")
    if retention_until < datetime.now(timezone.utc).date():
        raise CaptureError("--retain-until must be today or a future date.")
    return start, end, identity_checked_at, retention_until


def _bool(value: str | None) -> bool | None:
    if value is None or value == "":
        return None
    lowered = value.casefold()
    if lowered in ("true", "t", "1"):
        return True
    if lowered in ("false", "f", "0"):
        return False
    return None


def summarize(rows: list[dict[str, str]], selected_name: str) -> dict[str, object]:
    stamps: list[datetime] = []
    valid_stamps: list[datetime] = []
    for row in rows:
        raw = row.get("ingested_at_utc", "")
        if raw:
            stamp = parse_timestamp(raw, "ingested_at_utc")
            stamps.append(stamp)
            if _bool(row.get("position_valid")) is True:
                valid_stamps.append(stamp)
    intervals = [max(0.0, (later - earlier).total_seconds()) for earlier, later in zip(stamps, stamps[1:])]
    valid_intervals = [max(0.0, (later - earlier).total_seconds()) for earlier, later in zip(valid_stamps, valid_stamps[1:])]
    names = sorted({row["vessel_name"].strip() for row in rows if row.get("vessel_name", "").strip()})
    selected_normalized = normalize_name(selected_name)
    mismatches = sum(
        1 for row in rows
        if normalize_name(row.get("vessel_name")) is not None
        and normalize_name(row.get("vessel_name")) != selected_normalized
    )
    invalid_positions = sum(_bool(row.get("position_valid")) is False for row in rows)
    unknown_position_validity = sum(_bool(row.get("position_valid")) is None for row in rows)
    missing_reported_name = sum(not row.get("vessel_name", "").strip() for row in rows)
    missing_speed = sum(not row.get("speed_over_ground_knots", "").strip() for row in rows)
    missing_ais_second = sum(not row.get("ais_utc_second", "").strip() for row in rows)
    max_gap = max(intervals, default=0.0)
    max_valid_gap = max(valid_intervals, default=0.0)
    flags: list[str] = []
    if not rows:
        flags.append("NO_OBSERVATIONS")
    if invalid_positions:
        flags.append("INVALID_POSITION_FLAG_PRESENT")
    if unknown_position_validity:
        flags.append("POSITION_VALIDITY_UNKNOWN")
    if missing_reported_name:
        flags.append("AIS_NAME_MISSING")
    if missing_speed:
        flags.append("SPEED_MISSING")
    if missing_ais_second:
        flags.append("AIS_UTC_SECOND_MISSING")
    if mismatches:
        flags.append("REPORTED_NAME_MISMATCH")
    if max_gap > GAP_REVIEW_SECONDS or max_valid_gap > GAP_REVIEW_SECONDS:
        flags.append("GAP_OVER_10_MINUTES")
    return {
        "observation_count": len(rows),
        "first_ingested_at_utc": stamps[0].isoformat().replace("+00:00", "Z") if stamps else None,
        "last_ingested_at_utc": stamps[-1].isoformat().replace("+00:00", "Z") if stamps else None,
        "observed_span_seconds": round((stamps[-1] - stamps[0]).total_seconds(), 3) if len(stamps) > 1 else 0,
        "median_report_interval_seconds": round(median(intervals), 3) if intervals else None,
        "maximum_report_gap_seconds": round(max_gap, 3) if intervals else None,
        "gaps_over_10_minutes": sum(gap > GAP_REVIEW_SECONDS for gap in intervals),
        "valid_position_count": len(valid_stamps),
        "valid_position_span_seconds": round((valid_stamps[-1] - valid_stamps[0]).total_seconds(), 3) if len(valid_stamps) > 1 else 0,
        "maximum_valid_position_gap_seconds": round(max_valid_gap, 3) if valid_intervals else None,
        "valid_position_gaps_over_10_minutes": sum(gap > GAP_REVIEW_SECONDS for gap in valid_intervals),
        "position_valid_false_count": invalid_positions,
        "position_valid_unknown_count": unknown_position_validity,
        "missing_reported_name_count": missing_reported_name,
        "missing_speed_count": missing_speed,
        "missing_ais_utc_second_count": missing_ais_second,
        "reported_name_values": names,
        "reported_name_mismatch_count": mismatches,
        "automated_quality_status": "NO_REPORTS" if not rows else ("FLAGS_PRESENT" if flags else "NO_AUTOMATED_FLAGS"),
        "automated_quality_flags": flags,
        "operator_acceptance": "PENDING",
    }


def query_rows(mmsi: str, start: datetime, end: datetime) -> str:
    if shutil.which("psql") is None:
        raise CaptureError("psql was not found. Install the PostgreSQL client or rehearse with the offline fixture.")
    command = [
        "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
        "-v", f"mmsi={mmsi}",
        "-v", f"from_utc={start.isoformat()}",
        "-v", f"to_utc={end.isoformat()}",
        "-f", "-",
    ]
    psql_environment = {
        name: value
        for name, value in os.environ.items()
        if name.startswith("PG") or name in ("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL")
    }
    try:
        result = subprocess.run(command, input=COPY_SQL, text=True, encoding="utf-8", capture_output=True,
                                check=False, env=psql_environment)
    except OSError as exc:
        raise CaptureError("Unable to start psql; use the offline fixture for rehearsal.") from exc
    if result.returncode != 0:
        raise CaptureError("The database export failed. Check database connectivity and the selected schema; no provider key is used by this exporter.")
    return result.stdout


def read_rows(csv_text: str) -> list[dict[str, str]]:
    reader = csv.DictReader(io.StringIO(csv_text, newline=""))
    if reader.fieldnames != list(CSV_FIELDS):
        raise CaptureError("The database export returned an unexpected AIS observation schema.")
    rows = list(reader)
    if any(None in row or any(value is None for value in row.values()) for row in rows):
        raise CaptureError("The database export contains a malformed CSV row.")
    return rows


def validate_export_rows(rows: list[dict[str, str]], mmsi: str, start: datetime, end: datetime) -> None:
    for row in rows:
        if row["mmsi"] != mmsi or row["source"] != "AIS_STREAM":
            raise CaptureError("The database returned a report outside the selected MMSI/source scope.")
        stamp = parse_timestamp(row["ingested_at_utc"], "ingested_at_utc")
        if stamp < start or stamp > end:
            raise CaptureError("The database returned a report outside the requested time window.")


def manifest_for(args: argparse.Namespace, start: datetime, end: datetime,
                 identity_checked_at: datetime, retention_until: date,
                 csv_text: str, rows: list[dict[str, str]], exported_at: datetime) -> dict[str, object]:
    quality = summarize(rows, args.vessel_name)
    quality["operator_acceptance"] = args.operator_acceptance
    return {
        "format_version": 1,
        "capture_id": str(uuid.uuid4()),
        "classification": "AUTHENTIC_PROVIDER_CAPTURE",
        "source": "AISStream via DRIFT's existing consumer; source filter AIS_STREAM",
        "vessel": {
            "role": args.role.upper(),
            "name": args.vessel_name.strip(),
            "type": args.vessel_type.strip(),
            "imo": args.imo,
            "mmsi": args.mmsi,
            "identity_reference": args.identity_reference.strip(),
            "identity_checked_at_utc": identity_checked_at.isoformat().replace("+00:00", "Z"),
        },
        "window": {
            "from_utc_inclusive": start.isoformat().replace("+00:00", "Z"),
            "to_utc_inclusive": end.isoformat().replace("+00:00", "Z"),
            "timestamp_semantics": "DRIFT receipt time (vessel_observations.ingested_at), not AIS event time",
            "ais_utc_second_semantics": "AIS UTC second-of-minute when supplied; not a full timestamp",
        },
        "exported_at_utc": exported_at.isoformat().replace("+00:00", "Z"),
        "provider_usage_review": {
            "reference": args.terms_reference.strip(),
            "reviewed_at_utc": parse_timestamp(args.terms_reviewed_at, "--terms-reviewed-at").isoformat().replace("+00:00", "Z"),
            "replay_permission": args.replay_permission,
            "retain_until": retention_until.isoformat(),
        },
        "csv_file": Path(args.output).name,
        "csv_sha256": hashlib.sha256(csv_text.encode("utf-8")).hexdigest(),
        "data_quality": quality,
        "limitations": [
            "This export contains observations received by DRIFT, not a complete AIS history or guaranteed event timestamps.",
            "AIS vessel identity and position quality still require operator review against the cited identity source.",
            "This export does not establish a booking, voyage, transshipment, or cargo relationship.",
            "Automated quality flags are descriptive and do not approve this candidate for demonstration use.",
        ],
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--role", required=True, choices=("mother", "feeder"))
    result.add_argument("--vessel-name", required=True)
    result.add_argument("--vessel-type", required=True, help="Type recorded by the operator, e.g. container")
    result.add_argument("--imo", required=True)
    result.add_argument("--mmsi", required=True)
    result.add_argument("--identity-reference", required=True, help="Authoritative registry/source reference used to verify IMO and MMSI")
    result.add_argument("--identity-checked-at", required=True, help="ISO-8601 timestamp with timezone")
    result.add_argument("--from", dest="start", required=True, help="Inclusive ISO-8601 timestamp with timezone")
    result.add_argument("--to", dest="end", required=True, help="Inclusive ISO-8601 timestamp with timezone")
    result.add_argument("--terms-reference", required=True, help="Provider usage/retention review reference")
    result.add_argument("--terms-reviewed-at", required=True, help="ISO-8601 timestamp with timezone")
    result.add_argument("--replay-permission", required=True, choices=("APPROVED", "NOT_APPROVED", "UNKNOWN"))
    result.add_argument("--operator-acceptance", choices=("PENDING", "ACCEPTED", "REJECTED"), default="PENDING",
                        help="Set ACCEPTED only after the vessel and capture review is complete")
    result.add_argument("--retain-until", required=True, help="ISO-8601 date (YYYY-MM-DD)")
    result.add_argument("--output", required=True, type=Path)
    result.add_argument("--manifest", type=Path, help="Defaults to <output stem>.manifest.json")
    result.add_argument("--overwrite", action="store_true", help="Replace existing export and manifest files")
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        start, end, identity_checked_at, retention_until = validate_selection(args)
        manifest_path = args.manifest or args.output.with_name(f"{args.output.stem}.manifest.json")
        if args.output.resolve() == manifest_path.resolve():
            raise CaptureError("CSV and manifest output paths must be different.")
        if not args.overwrite and (args.output.exists() or manifest_path.exists()):
            raise CaptureError("Output already exists; choose a new path or pass --overwrite.")
        exported_at = datetime.now(timezone.utc)
        csv_text = query_rows(args.mmsi, start, end)
        rows = read_rows(csv_text)
        validate_export_rows(rows, args.mmsi, start, end)
        manifest = manifest_for(args, start, end, identity_checked_at, retention_until, csv_text, rows, exported_at)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(csv_text, encoding="utf-8", newline="")
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    except (CaptureError, OSError) as exc:
        print(f"AIS capture export failed: {exc}", file=sys.stderr)
        return 2
    print(f"Exported {len(rows)} AIS_STREAM observations to {args.output}; quality={manifest['data_quality']['automated_quality_status']}.")
    return 0 if rows else 3


if __name__ == "__main__":
    raise SystemExit(main())
