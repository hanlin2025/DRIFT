#!/usr/bin/env python3
"""Append one daily, offline CDG-201 readiness snapshot.

It delegates all evidence assessment to the established maturation and
ETA-persistence scripts. The append-only trend is a monitor, not a training
dataset, and never changes MPA/NEA raw files or collection schedules.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys
from datetime import date, datetime, timezone
from pathlib import Path

BASE = Path(os.environ.get("CDG201_DATA_ROOT", Path.home() / "opt/data/change_detection"))
TOOLS = BASE / "tools"
OUT = BASE / "analysis/ai_readiness"
TREND = OUT / "daily_readiness_trend.csv"
TREND_V2 = OUT / "daily_readiness_trend_v2.csv"
SNAPSHOTS = OUT / "daily_snapshots"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def run_existing() -> dict:
    process = subprocess.run([sys.executable, str(TOOLS / "run_ai_maturation_check.py")],
                             text=True, capture_output=True)
    return {"exit_code": process.returncode, "stdout": process.stdout.strip(),
            "stderr": process.stderr.strip()}


def write_failed_daily_attempt(run: dict, when: datetime) -> Path:
    """Record a failed refresh separately; never append stale readiness metrics."""
    SNAPSHOTS.mkdir(parents=True, exist_ok=True)
    stamp = when.strftime("%Y%m%dT%H%M%SZ")
    path = SNAPSHOTS / f"ai_readiness_failed_refresh_{stamp}.json"
    path.write_text(json.dumps({
        "snapshot_utc": when.isoformat(),
        "status": "failed_before_readiness_snapshot",
        "maturation_exit_code": run["exit_code"],
        "maturation_stdout": run["stdout"],
        "maturation_stderr": run["stderr"],
        "policy": "No daily_readiness_trend row was appended because its inputs may be stale.",
    }, indent=2) + "\n")
    return path


def rows(path: Path) -> list[dict]:
    return list(csv.DictReader(path.open())) if path.exists() else []


def visit_proxies(matched: list[dict]) -> list[str]:
    return sorted({"|".join((row.get("eta_imo", ""), row.get("eta_location_from", ""),
                               row.get("eta_location_to", ""), row.get("actual_time", ""))) for row in matched})


def prior_known_visits() -> set[str]:
    for path in reversed(sorted(SNAPSHOTS.glob("ai_readiness_*.json"))):
        try:
            previous = json.loads(path.read_text())
        except json.JSONDecodeError:
            continue
        values = previous.get("matched_visit_proxies")
        if isinstance(values, list):
            return {value for value in values if isinstance(value, str)}
    return set()


def append_trend(result: dict) -> Path:
    """Append only to a CSV with the current schema.

    The original trend is evidence from an earlier schema and is never
    rewritten.  A changed result contract starts a versioned trend instead of
    serialising values into the wrong legacy columns.
    """
    fieldnames = list(result)
    target = TREND
    if TREND.exists():
        with TREND.open(newline="") as handle:
            header = next(csv.reader(handle), [])
        if header != fieldnames:
            target = TREND_V2
    new = not target.exists()
    with target.open("a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        if new:
            writer.writeheader()
        writer.writerow({k: json.dumps(v, sort_keys=True) if isinstance(v, (dict, list)) else v
                         for k, v in result.items()})
    return target


def checkpoint_decision(matched: int, vessels: int, prediction_dates: int, baseline_available: bool) -> str:
    if matched < 30 or vessels < 10 or prediction_dates < 3:
        return "NO_GO_INSUFFICIENT_CONFIDENT_LABELS"
    if not baseline_available:
        return "CONDITIONAL_GO_LABELS_READY_BASELINE_NOT_YET_AVAILABLE"
    return "CONDITIONAL_GO_BASELINE_AVAILABLE_EXPLORATORY_ONLY"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--as-of-date", type=date.fromisoformat,
                        help="Optional reporting date (YYYY-MM-DD); does not alter evidence filtering.")
    args = parser.parse_args()
    run = run_existing()
    when = utc_now()
    reporting_date = args.as_of_date or when.date()
    OUT.mkdir(parents=True, exist_ok=True); SNAPSHOTS.mkdir(parents=True, exist_ok=True)
    if run["exit_code"]:
        failure = write_failed_daily_attempt(run, when)
        print(json.dumps({"status": "failed", "failure_record": str(failure),
                          "maturation_exit_code": run["exit_code"]}, indent=2))
        raise SystemExit(run["exit_code"])
    monitor = json.loads((OUT / "AI_READINESS_MONITOR.json").read_text())
    maturity_check = json.loads((OUT / "AI_MATURATION_CHECK.json").read_text())
    baseline = json.loads((OUT / "ETA_PERSISTENCE_BASELINE_REPORT.json").read_text())
    labels = rows(OUT / "prospective_eta_actual_candidates.csv")
    diagnostic = json.loads((OUT / "ETA_OUTCOME_DIAGNOSTIC_REPORT.json").read_text())
    matched = [r for r in labels if r.get("label_status") == "matched"]
    # A reported event timestamp plus IMO/location is the strongest available
    # visit proxy. It remains a proxy until MPA exposes a stable visit ID.
    proxy_values = visit_proxies(matched)
    visits = set(proxy_values)
    previous_visits = prior_known_visits()
    vessels = {r.get("eta_imo") for r in matched if r.get("eta_imo")}
    prediction_dates = {r.get("eta_collection_utc", "")[:10] for r in matched if r.get("eta_collection_utc")}
    status_counts = monitor.get("label_status_counts", {})
    result = {
        "snapshot_utc": when.isoformat(), "reporting_date": reporting_date.isoformat(),
        "maturation_exit_code": run["exit_code"],
        "prospective_candidates": monitor.get("prospective_eta_candidates", 0),
        "pending_candidates": status_counts.get("pending", 0),
        "matched_candidate_rows": len(matched), "matched_unique_vessel_visits": len(visits),
        "matched_distinct_vessels": len(vessels), "matched_prediction_dates": len(prediction_dates),
        "matched_visit_proxies": proxy_values,
        "new_matched_unique_vessel_visits_since_prior_snapshot": len(set(proxy_values) - previous_visits),
        "ambiguous_candidates": status_counts.get("ambiguous", 0),
        "unresolved_coverage_candidates": status_counts.get("unresolved_due_to_coverage", 0),
        "invalid_candidates": status_counts.get("invalid", 0),
        "reported_arrival_events_examined": monitor.get("valid_reported_arrival_events", 0),
        "reported_arrival_coverage_latest_utc": diagnostic.get("latest_successful_reported_arrival_collection_utc"),
        "outcome_diagnostic_status_counts": diagnostic.get("status_counts", {}),
        "leakage_safe_weather_prospective_candidate_rows": maturity_check.get("leakage_safe_weather_prospective_candidate_rows", 0),
        "leakage_safe_weather_matched_rows": baseline.get("weather_eligible_evaluation_examples", 0),
        "eta_persistence_metrics_available": baseline.get("eta_persistence_metrics", {}).get("available", False),
        "eta_persistence_metrics": baseline.get("eta_persistence_metrics", {}),
        "eta_persistence_per_observation": baseline.get("eta_persistence_per_observation", baseline.get("eta_persistence_metrics", {})),
        "eta_persistence_per_distinct_vessel_visit": baseline.get("eta_persistence_per_distinct_vessel_visit", {}),
        "decision": checkpoint_decision(len(matched), len(vessels), len(prediction_dates),
                                        baseline.get("eta_persistence_metrics", {}).get("available", False)),
        "policy": "Missing/ambiguous outcomes remain censored. Retrospective NEA backfills are never leakage-safe features. Visit proxies are not official visit IDs.",
    }
    stamp = when.strftime("%Y%m%dT%H%M%SZ")
    (SNAPSHOTS / f"ai_readiness_{reporting_date.isoformat()}_{stamp}.json").write_text(json.dumps(result, indent=2) + "\n")
    trend = append_trend(result)
    # Only explicit checkpoint dates receive an additional immutable digest.
    if reporting_date.isoformat() in {"2026-10-30", "2026-11-02"}:
        (SNAPSHOTS / f"DECISION_{reporting_date.isoformat()}_{stamp}.md").write_text(
            f"# CDG-201 readiness decision — {reporting_date.isoformat()}\n\n"
            f"Decision: **{result['decision']}**.\n\n"
            f"Matched unique vessel visits: {len(visits)}; distinct vessels: {len(vessels)}; prediction dates: {len(prediction_dates)}.\n\n"
            f"Pending: {result['pending_candidates']}; reported arrivals examined: {result['reported_arrival_events_examined']}; "
            f"leakage-safe weather rows: {result['leakage_safe_weather_prospective_candidate_rows']}.\n\n"
            "No model metric is reported unless confidence-gated labels exist.\n")
    print(json.dumps({**result, "trend_file": str(trend)}, indent=2))
    if run["exit_code"]:
        raise SystemExit(run["exit_code"])


if __name__ == "__main__":
    main()

