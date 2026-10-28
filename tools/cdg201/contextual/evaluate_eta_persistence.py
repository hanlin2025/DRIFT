#!/usr/bin/env python3
"""Offline ETA-persistence evaluation; never changes raw MPA/NEA evidence.

The baseline predicts the latest ETA published at the prediction cutoff without
correction. Only maturation rows marked ``matched`` become evaluation examples.
Weather is carried only as a point-in-time eligibility flag, never used by this
baseline or attributed as a cause of any ETA error.
"""
import argparse
import csv
import json
from datetime import datetime, timezone
from pathlib import Path


def parse_source_time(value):
    try:
        return datetime.fromisoformat(value)
    except (TypeError, ValueError):
        return None


def weather_is_safe(weather_row, cutoff):
    """The weather builder has already enforced source/retrieval/issuance rules."""
    return bool(weather_row and weather_row.get("weather_join_status") == "eligible_live_weather_available" and cutoff)


def make_evaluation_rows(labels, weather_by_key):
    results = []
    for label in labels:
        if label.get("label_status") != "matched":
            continue
        prediction, actual = parse_source_time(label.get("eta_revised_source_time")), parse_source_time(label.get("actual_time"))
        cutoff = label.get("eta_collection_utc")
        if not prediction or not actual or not cutoff:
            continue
        key = (label.get("eta_imo"), cutoff, label.get("eta_run_id"))
        weather = weather_by_key.get(key)
        signed_minutes = int((actual - prediction).total_seconds() / 60)
        results.append({
            "imo": label.get("eta_imo"), "vessel_name": label.get("vessel_name"),
            "prediction_cutoff_utc": cutoff, "published_eta_source_string": label.get("eta_revised_source_time"),
            "reported_arrival_source_string": label.get("actual_time"),
            "signed_error_minutes": signed_minutes, "absolute_error_minutes": abs(signed_minutes),
            "baseline": "eta_persistence_no_correction", "weather_feature_eligible": weather_is_safe(weather, cutoff),
            "weather_join_status": weather.get("weather_join_status") if weather else "no_weather_join_record",
            "eta_manifest": label.get("eta_manifest"), "actual_manifest": label.get("actual_manifest"),
            "leakage_guard": "Reported arrival is an outcome only; all features must be retrieved at or before prediction_cutoff_utc.",
        })
    return results


def metrics(rows):
    if not rows:
        return {"available": False, "reason": "zero_qualifying_matched_labels", "examples": 0,
                "signed_error_mean_minutes": None, "mae_minutes": None, "median_absolute_error_minutes": None}
    signed = [row["signed_error_minutes"] for row in rows]
    absolute = sorted(row["absolute_error_minutes"] for row in rows)
    middle = len(absolute) // 2
    median = absolute[middle] if len(absolute) % 2 else (absolute[middle - 1] + absolute[middle]) / 2
    return {"available": True, "examples": len(rows), "signed_error_mean_minutes": sum(signed) / len(signed),
            "mae_minutes": sum(absolute) / len(absolute), "median_absolute_error_minutes": median}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--labels", type=Path, required=True)
    parser.add_argument("--weather", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(); labels_path = args.labels; weather_path = args.weather; output = args.output; output.mkdir(parents=True, exist_ok=True)
    labels = list(csv.DictReader(labels_path.open())) if labels_path.exists() else []
    weather = list(csv.DictReader(weather_path.open())) if weather_path.exists() else []
    weather_by_key = {(row.get("imo"), row.get("prediction_cutoff_utc"), row.get("mpa_run_id")): row for row in weather}
    examples = make_evaluation_rows(labels, weather_by_key)
    path = output / "eta_persistence_evaluation_examples.csv"
    fields = list(examples[0]) if examples else ["imo", "signed_error_minutes", "absolute_error_minutes"]
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields); writer.writeheader(); writer.writerows(examples)
    status_counts = {}
    for row in labels: status_counts[row.get("label_status", "unknown")] = status_counts.get(row.get("label_status", "unknown"), 0) + 1
    report = {"generated_utc": datetime.now(timezone.utc).isoformat(), "candidate_label_status_counts": status_counts,
              "weather_join_records": len(weather), "weather_eligible_evaluation_examples": sum(row["weather_feature_eligible"] for row in examples),
              "eta_persistence_metrics": metrics(examples), "evaluation_examples_file": str(path),
              "leakage_policy": "Only matched prospective labels qualify. Reported arrivals are outcomes only; post-cutoff MPA/NEA data never becomes a feature."}
    (output / "ETA_PERSISTENCE_BASELINE_REPORT.json").write_text(json.dumps(report, indent=2) + "\n")
    (output / "ETA_PERSISTENCE_BASELINE_REPORT.md").write_text(
        "# ETA-persistence baseline evaluation\n\n"
        f"Candidate label statuses: {status_counts}.\n\n"
        f"Metrics: {report['eta_persistence_metrics']}.\n\n"
        "No metric is emitted until a prospective ETA has one confidently matched reported-arrival outcome. Weather eligibility is audited but is not used by the persistence baseline.\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
