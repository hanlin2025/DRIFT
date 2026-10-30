#!/usr/bin/env python3
"""Offline conservative reported arrival/departure pairing; never berth proof."""
import argparse
import csv
import json
from datetime import datetime
from pathlib import Path


def timestamp(row): return datetime.fromisoformat(row["timestamp_raw"])


def run(arrivals, departures, hours):
    paired, used, ambiguous = [], set(), []
    for arrival in sorted([row for row in arrivals if row["imo"]], key=timestamp):
        candidates = [departure for departure in departures if departure["imo"] == arrival["imo"] and 0 < (timestamp(departure) - timestamp(arrival)).total_seconds() <= hours * 3600 and departure["event_key"] not in used]
        intervening = [other for other in arrivals if other["imo"] == arrival["imo"] and timestamp(arrival) < timestamp(other) < (timestamp(candidates[0]) if candidates else timestamp(arrival))]
        if len(candidates) == 1 and not intervening:
            departure = candidates[0]; used.add(departure["event_key"])
            paired.append({"imo": arrival["imo"], "vessel_name": arrival["vessel_name"], "arrival": arrival["timestamp_raw"], "departure": departure["timestamp_raw"], "hours": round((timestamp(departure) - timestamp(arrival)).total_seconds() / 3600, 2), "arrival_raw": arrival["raw_file"], "departure_raw": departure["raw_file"], "confidence": "unambiguous_reported_pair_not_berth"})
        elif candidates: ambiguous.append({"imo": arrival["imo"], "arrival": arrival["timestamp_raw"], "candidate_departures": len(candidates), "reason": "competing_departures_or_intervening_arrival"})
    return paired, ambiguous


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Normalization output directory")
    parser.add_argument("--output", type=Path, required=True, help="Pairing report directory")
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    arrivals = list(csv.DictReader((args.input / "arrivals.csv").open())); departures = list(csv.DictReader((args.input / "departures.csv").open()))
    results = {}
    for hours in (24, 48, 72):
        pairs, ambiguous = run(arrivals, departures, hours)
        results[hours] = {"pairs": pairs, "ambiguous_rows": ambiguous}
    (args.output / "pairing_quality.json").write_text(json.dumps({hours: {"accepted": len(value["pairs"]), "ambiguous": len(value["ambiguous_rows"])} for hours, value in results.items()}, indent=2))
    chosen = results[48]["pairs"]
    with (args.output / "candidate_pairs_48h.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=chosen[0].keys() if chosen else ["imo"]); writer.writeheader(); writer.writerows(chosen)
    print(json.dumps({hours: (len(value["pairs"]), len(value["ambiguous_rows"])) for hours, value in results.items()}))


if __name__ == "__main__": main()
