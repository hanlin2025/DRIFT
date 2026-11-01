#!/usr/bin/env python3
"""Offline ETA-persistence evaluation; never changes raw MPA/NEA evidence.

The baseline predicts the latest ETA published at the prediction cutoff without
correction. Only maturation rows marked ``matched`` become evaluation examples.
Weather is carried only as a point-in-time eligibility flag, never used by this
baseline or attributed as a cause of any ETA error.
"""
import csv
import json
import os
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
            "eta_location_from": label.get("eta_location_from"), "eta_location_to": label.get("eta_location_to"),
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


def visit_proxy(row):
    """Best available visit proxy; never presented as an official visit ID."""
    return (row.get("imo", ""), row.get("eta_location_from", ""),
            row.get("eta_location_to", ""), row.get("reported_arrival_source_string", ""))


def per_distinct_visit_rows(rows):
    """Keep the latest eligible cutoff per visit proxy without using its error."""
    selected = {}
    for row in rows:
        key = visit_proxy(row)
        current = selected.get(key)
        if current is None or row.get("prediction_cutoff_utc", "") > current.get("prediction_cutoff_utc", ""):
            selected[key] = row
    return list(selected.values())


def main():
    base = Path(os.environ.get("CDG201_DATA_ROOT", Path.home() / "opt/data/change_detection"))
    labels_path = base / "analysis/ai_readiness/prospective_eta_actual_candidates.csv"
    weather_path = base / "analysis/ai_dataset_readiness/mpa_nea_point_in_time_inspection.csv"
    output = base / "analysis/ai_readiness"; output.mkdir(parents=True, exist_ok=True)
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
    distinct_visit_rows = per_distinct_visit_rows(examples)
    report = {"generated_utc": datetime.now(timezone.utc).isoformat(), "candidate_label_status_counts": status_counts,
              "weather_join_records": len(weather), "weather_eligible_evaluation_examples": sum(row["weather_feature_eligible"] for row in examples),
              "eta_persistence_metrics": metrics(examples),
              "eta_persistence_per_observation": metrics(examples),
              "eta_persistence_per_distinct_vessel_visit": metrics(distinct_visit_rows),
              "independence": {"visit_proxy_definition": "IMO + source origin + source destination + reported-arrival source timestamp",
                               "observation_rows": len(examples), "distinct_visit_proxies": len(distinct_visit_rows),
                               "weighting": "one latest eligible prediction cutoff per visit proxy; selection does not use error"},
              "evaluation_examples_file": str(path),
              "leakage_policy": "Only matched prospective labels qualify. Reported arrivals are outcomes only; post-cutoff MPA/NEA data never becomes a feature."}
    (output / "ETA_PERSISTENCE_BASELINE_REPORT.json").write_text(json.dumps(report, indent=2) + "\n")
    (output / "ETA_PERSISTENCE_BASELINE_REPORT.md").write_text(
        "# ETA-persistence baseline evaluation\n\n"
        f"Candidate label statuses: {status_counts}.\n\n"
        f"Per-observation metrics: {report['eta_persistence_per_observation']}.\n\n"
        f"Per-distinct-visit-proxy metrics: {report['eta_persistence_per_distinct_vessel_visit']}.\n\n"
        f"Independence/weighting: {report['independence']}.\n\n"
        "Signed error is reported arrival minus published ETA: positive means the vessel was reported later than ETA; negative means earlier. No metric is emitted until a prospective ETA has one confidently matched reported-arrival outcome. Weather eligibility is audited but is not used by the persistence baseline.\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
