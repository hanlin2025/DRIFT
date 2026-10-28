#!/usr/bin/env python3
"""Manifest-aware, offline normalization of reported MPA arrivals/departures."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


def imo(value):
    value = str(value or "")
    return value if len(value) == 7 and value.isdigit() and sum(int(value[i]) * (7 - i) for i in range(6)) % 10 == int(value[6]) else ""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True, help="MPA raw snapshot/manifest directory")
    parser.add_argument("--output", type=Path, required=True, help="Derived normalization directory")
    args = parser.parse_args(); raw_root = args.root; output = args.output; output.mkdir(parents=True, exist_ok=True)
    rows = {"arrivals": [], "departures": []}; bad = []
    for manifest_path in raw_root.glob("*.manifest.json"):
        try: manifest = json.loads(manifest_path.read_text())
        except Exception: continue
        dataset = manifest.get("dataset"); raw_name = manifest.get("raw_file", ""); raw_path = raw_root / raw_name
        if dataset not in rows: continue
        if manifest.get("error") or not raw_name or not raw_path.exists() or hashlib.sha256(raw_path.read_bytes()).hexdigest() != manifest.get("sha256"):
            bad.append({"manifest": manifest_path.name, "dataset": dataset, "reason": "error_or_checksum"}); continue
        try: data = json.loads(raw_path.read_text())
        except Exception: bad.append({"manifest": manifest_path.name, "dataset": dataset, "reason": "malformed_json"}); continue
        if not isinstance(data, list): bad.append({"manifest": manifest_path.name, "dataset": dataset, "reason": "not_array"}); continue
        for record in data:
            particulars = record.get("vesselParticulars", {})
            stamp = record.get("arrivedTime") or record.get("departedTime") or record.get("arrivalTime") or record.get("departureTime") or record.get("time")
            if not stamp: continue
            rows[dataset].append({"event_key": "|".join([imo(particulars.get("imoNumber")), str(stamp), str(record.get("locationTo"))]), "imo": imo(particulars.get("imoNumber")), "imo_raw": particulars.get("imoNumber"), "vessel_name": particulars.get("vesselName"), "call_sign": particulars.get("callSign"), "timestamp_raw": stamp, "timestamp_timezone": "UNSPECIFIED", "location_from": record.get("locationFrom"), "location_to": record.get("locationTo"), "raw_file": raw_name, "manifest": manifest_path.name, "collection_utc": manifest.get("retrieval_started_utc")})
    for dataset, dataset_rows in rows.items():
        seen = set(); unique = []
        for row in dataset_rows:
            if row["event_key"] not in seen: seen.add(row["event_key"]); unique.append(row)
        rows[dataset] = unique
        with (output / f"{dataset}.csv").open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=unique[0].keys() if unique else ["event_key"]); writer.writeheader(); writer.writerows(unique)
    (output / "exclusions.json").write_text(json.dumps(bad, indent=2))
    print(json.dumps({"arrivals": len(rows["arrivals"]), "departures": len(rows["departures"]), "exclusions": len(bad)}))


if __name__ == "__main__": main()
