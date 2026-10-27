#!/usr/bin/env python3
"""Run the existing CDG-201 label and feature readiness steps as one offline check.

This never contacts an API, changes raw evidence, or regards a missing outcome as
a negative.  It merely refreshes derived monitoring artefacts from append-only
MPA/NEA manifests.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


BASE = Path(os.environ.get("CDG201_DATA_ROOT", Path.home() / "opt/data/change_detection"))
TOOLS = BASE / "tools"
OUT = BASE / "analysis/ai_readiness"
FAILURES = OUT / "workflow_failures"

# These stages intentionally operate on derived artefacts only.  The revision
# detector must run first: every later stage consumes expected_revisions.csv.
WORKFLOW_STAGES = (
    "detect_mpa_expected_revisions.py",
    "build_eta_ground_truth_candidates.py",
    "build_mpa_nea_inspection_join.py",
    "evaluate_eta_persistence.py",
    "audit_matured_eta_outcomes.py",
    "diagnose_eta_outcomes.py",
)


def run(script: str) -> dict:
    completed = subprocess.run([sys.executable, str(TOOLS / script)], text=True,
                               capture_output=True)
    return {"script": script, "exit_code": completed.returncode,
            "stdout": completed.stdout.strip(), "stderr": completed.stderr.strip()}


def run_workflow(run_stage=run) -> list[dict]:
    """Run dependent stages in order and stop at the first failure.

    Continuing would allow stale derived files to be presented as a fresh daily
    assessment.  Raw manifests and previous successful reports are untouched.
    """
    results = []
    for script in WORKFLOW_STAGES:
        result = run_stage(script)
        results.append(result)
        if result["exit_code"] != 0:
            break
    return results


def write_failure(results: list[dict]) -> Path:
    """Append a failure record without replacing the last valid assessment."""
    FAILURES.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = FAILURES / f"ai_maturation_workflow_failure_{stamp}.json"
    path.write_text(json.dumps({
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "status": "failed_before_fresh_readiness_assessment",
        "completed_or_attempted_stages": results,
        "policy": "No daily trend entry or refreshed readiness report was written; prior successful derived reports remain historical evidence.",
    }, indent=2) + "\n")
    return path


def main() -> None:
    # Order matters: revisions use raw MPA expected snapshots; labels then use
    # refreshed revisions; weather and the baseline consume those refreshed labels.
    results = run_workflow()
    failed = [result for result in results if result["exit_code"] != 0]
    if failed:
        failure = write_failure(results)
        print(json.dumps({"status": "failed", "failure_record": str(failure),
                          "command_results": results}, indent=2))
        raise SystemExit(1)
    monitor_path = OUT / "AI_READINESS_MONITOR.json"
    monitor = json.loads(monitor_path.read_text()) if monitor_path.exists() else {}
    weather_csv = BASE / "analysis/ai_dataset_readiness/mpa_nea_point_in_time_inspection.csv"
    weather_rows = []
    if weather_csv.exists():
        import csv
        weather_rows = list(csv.DictReader(weather_csv.open()))
    # The weather report includes all observed revisions. Restrict this monitor
    # to the prospective-label cohort by the same identity/cutoff/run key used
    # by the baseline.
    label_path = OUT / "prospective_eta_actual_candidates.csv"
    labels = []
    if label_path.exists():
        import csv
        labels = list(csv.DictReader(label_path.open()))
    prospective_keys = {(row.get("eta_imo"), row.get("eta_collection_utc"), row.get("eta_run_id")) for row in labels}
    weather_safe = sum(
        row.get("weather_join_status") == "eligible_live_weather_available"
        and (row.get("imo"), row.get("prediction_cutoff_utc"), row.get("mpa_run_id")) in prospective_keys
        for row in weather_rows
    )
    report = {
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "command_results": results,
        "prospective_eta_candidates": monitor.get("prospective_eta_candidates", 0),
        "label_status_counts": monitor.get("label_status_counts", {}),
        "leakage_safe_weather_prospective_candidate_rows": weather_safe,
        "eta_persistence_ready": monitor.get("ready_for_eta_persistence_evaluation", False),
        "cadence_recommendation": "Run once daily after reported-arrival collection has completed; it is offline and does not require a scheduler.",
        "policy": "Only prospective ETA observations with confidently matched later reported arrivals are evaluation labels. Historical weather backfills remain ineligible as point-in-time features.",
        "status": "failed" if failed else "ok",
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "AI_MATURATION_CHECK.json").write_text(json.dumps(report, indent=2) + "\n")
    (OUT / "AI_MATURATION_CHECK.md").write_text(
        "# CDG-201 AI maturation check\n\n"
        f"Prospective ETA candidates: {report['prospective_eta_candidates']}.\n\n"
        f"Label states: {report['label_status_counts']}.\n\n"
        f"Leakage-safe live-weather prospective-candidate rows: {weather_safe}.\n\n"
        f"ETA-persistence evaluation ready: {report['eta_persistence_ready']}.\n\n"
        "Run daily after scheduled reported-arrival collection. This process is offline; it creates no scheduler and never converts missing outcomes into failures.\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

