#!/usr/bin/env python3
"""Build a leakage-safe inspection join; never changes raw collection evidence."""
import argparse
import csv
import hashlib
import json
from datetime import datetime
from pathlib import Path


def parse(value):
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None


def valid_nea_snapshots(root):
    """Only success/checksum-valid live data can be a prospective feature."""
    snapshots = []
    for path in root.glob("*.manifest.json"):
        try:
            meta = json.loads(path.read_text())
        except json.JSONDecodeError:
            continue
        raw_name = meta.get("raw_file") or ""
        raw = root / raw_name
        if (meta.get("collection_label") != "LIVE_SNAPSHOT" or meta.get("error") or
                meta.get("http_status") != 200 or not raw_name or not raw.exists() or
                hashlib.sha256(raw.read_bytes()).hexdigest() != meta.get("sha256")):
            continue
        snapshots.append({"dataset": meta.get("dataset"), "manifest": path.name,
                          "raw_file": raw_name, "retrieval_utc": meta.get("retrieval_completed_utc"),
                          "source_timestamp": meta.get("source_first_timestamp"),
                          "issuance": meta.get("forecast_issuance_timestamp"),
                          "valid_period": json.dumps(meta.get("forecast_valid_period") or {}, sort_keys=True),
                          "records": meta.get("record_count")})
    return snapshots


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revisions", type=Path, required=True)
    parser.add_argument("--weather-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(); revisions = args.revisions; weather_root = args.weather_root; out = args.output; out.mkdir(parents=True, exist_ok=True)
    weather = valid_nea_snapshots(weather_root)
    output = []
    with revisions.open() as handle:
        for event in csv.DictReader(handle):
            cutoff = parse(event.get("latest_collection_utc"))
            eligible = []
            for snapshot in weather:
                retrieved, observed = parse(snapshot["retrieval_utc"]), parse(snapshot["source_timestamp"])
                issued = parse(snapshot["issuance"]) if snapshot["issuance"] else None
                # Forecasts need both retrieval and issuance before cutoff. For
                # observations, source reading and retrieval must precede cutoff.
                if snapshot["dataset"] == "two_hr_forecast":
                    safe = cutoff and retrieved and issued and retrieved <= cutoff and issued <= cutoff
                else:
                    safe = cutoff and retrieved and observed and retrieved <= cutoff and observed <= cutoff
                if safe:
                    eligible.append(snapshot)
            output.append({
                "imo": event["imo"], "vessel_name": event["vessel_name"], "event_type": "expected_arrival_revision",
                "prediction_cutoff_utc": event["latest_collection_utc"], "revised_expected_source_string": event["revised_expected"],
                "location_from": event["location_from"], "location_to": event["location_to"],
                "revision_minutes": event["revision_minutes"], "mpa_manifest": event["latest_manifest"],
                "mpa_run_id": event["latest_run_id"], "weather_join_status": "eligible_live_weather_available" if eligible else "no_leakage_safe_live_weather_before_cutoff",
                "eligible_weather_snapshot_count": len(eligible),
                "weather_manifest_refs": ";".join(x["manifest"] for x in eligible),
                "weather_provenance_note": "Historical backfills excluded. Station/area selection unavailable: MPA expected feed has location codes but no vessel coordinates or official code-to-station mapping."})
    fields = list(output[0]) if output else ["imo"]
    path = out / "mpa_nea_point_in_time_inspection.csv"
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields); writer.writeheader(); writer.writerows(output)
    report = ["# MPA–NEA weather feature readiness", "",
              f"Candidate MPA revision observations: {len(output)}.",
              f"Leakage-safe NEA joins available: {sum(x['eligible_weather_snapshot_count'] > 0 for x in output)}.",
              f"Validated live NEA snapshots inspected: {len(weather)}.", "",
              "Historical NEA backfill files are excluded because they were retrieved after the MPA cutoffs. The current live smoke snapshots were also collected after every stored MPA revision cutoff, so they cannot be used as features for those examples.",
              "Weather observation timestamps and forecast issuance/validity are preserved. No vessel coordinates or documented mapping from MPA location codes to NEA stations/forecast areas exists locally, so station or area values are not selected or averaged.",
              "A future live join must choose only the latest checksum-valid live observation whose source and retrieval timestamps precede the MPA cutoff; forecasts additionally require issuance before cutoff. Weather must remain an association feature, not a claimed cause of delay."]
    (out / "WEATHER_FEATURE_READINESS.md").write_text("\n".join(report) + "\n")
    print(json.dumps({"candidate_events": len(output), "eligible_weather_joins": sum(x["eligible_weather_snapshot_count"] > 0 for x in output), "live_snapshots": len(weather), "output": str(path)}))


if __name__ == "__main__":
    main()
