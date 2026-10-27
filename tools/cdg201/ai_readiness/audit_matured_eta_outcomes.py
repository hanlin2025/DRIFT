#!/usr/bin/env python3
"""Produce a deterministic, provenance-only audit of matched ETA outcomes."""
from __future__ import annotations

import csv
import json
import os
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from cdg201_time import parse_mpa_source, parse_retrieval_utc

BASE = Path(os.environ.get("CDG201_DATA_ROOT", Path.home() / "opt/data/change_detection"))
OUT = BASE / "analysis/ai_readiness"


def proxy(row: dict) -> tuple[str, str, str, str]:
    return (row.get("eta_imo", ""), row.get("eta_location_from", ""), row.get("eta_location_to", ""), row.get("actual_time", ""))


def chronology_valid(row: dict) -> bool:
    cutoff = parse_retrieval_utc(row.get("eta_collection_utc"))
    reported_seen = parse_retrieval_utc(row.get("actual_collection_utc"))
    actual = parse_mpa_source(row.get("actual_time"))
    return bool(cutoff and reported_seen and actual and cutoff < reported_seen and cutoff < actual.astimezone(timezone.utc))


def absolute_error(row: dict) -> int:
    actual = parse_mpa_source(row.get("actual_time"))
    prediction = parse_mpa_source(row.get("eta_revised_source_time"))
    return abs(int((actual - prediction).total_seconds() / 60)) if actual and prediction else -1


def sample_rows(rows: list[dict]) -> list[dict]:
    groups = defaultdict(list)
    for row in rows:
        groups[proxy(row)].append(row)
    chosen = [max(group, key=lambda row: row.get("eta_collection_utc", "")) for group in groups.values() if len(group) > 1]
    chosen_proxies = {proxy(row) for row in chosen}
    for row in sorted(rows, key=lambda row: (absolute_error(row), row.get("eta_imo", "")), reverse=True):
        if proxy(row) not in chosen_proxies:
            chosen.append(row)
            chosen_proxies.add(proxy(row))
        if len(chosen) >= 12:
            break
    return chosen[:12]


def main() -> None:
    labels_path = OUT / "prospective_eta_actual_candidates.csv"
    rows = [row for row in csv.DictReader(labels_path.open()) if row.get("label_status") == "matched"]
    groups = defaultdict(list)
    for row in rows:
        groups[proxy(row)].append(row)
    fields = ["eta_imo", "vessel_name", "eta_collection_utc", "eta_revised_source_time", "actual_time", "actual_collection_utc", "eta_location_from", "eta_location_to", "eta_manifest", "actual_manifest", "actual_raw_file", "exclusion_or_status_reason", "chronology_valid", "visit_proxy_repeat_count"]
    output = []
    for row in sample_rows(rows):
        audit = {field: row.get(field, "") for field in fields}
        audit["chronology_valid"] = chronology_valid(row)
        audit["visit_proxy_repeat_count"] = len(groups[proxy(row)])
        output.append(audit)
    with (OUT / "matured_eta_outcome_audit_sample.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields); writer.writeheader(); writer.writerows(output)
    summary = {"generated_utc": datetime.now(timezone.utc).isoformat(), "matched_rows": len(rows), "distinct_visit_proxies": len(groups), "prediction_dates": sorted({row.get("eta_collection_utc", "")[:10] for row in rows}), "distinct_vessels": len({row.get("eta_imo") for row in rows}), "repeat_visit_proxy_groups": sum(len(group) > 1 for group in groups.values()), "all_matched_rows_have_retrieval_chronology": all(chronology_valid(row) for row in rows), "all_matched_rows_have_source_references": all(all(row.get(field) for field in ("eta_manifest", "actual_manifest", "actual_raw_file")) for row in rows), "sample_size": len(output), "biases": ["Matched rows are conditioned on a later, context-compatible reported-arrival observation.", "Repeated snapshots for one visit make row-level metrics overweight that visit.", "Only two prediction dates prevent temporal-holdout inference.", "MPA location code/context is a matching screen, not verified physical port/berth identity."], "cdg40_comparison": "unavailable: no stored, point-in-time CDG-40 speed-and-distance prediction artifact matched to these exact cutoffs and targets", "weather": "Only live weather retrieved at/before cutoff is feature-eligible; historical backfills remain excluded."}
    (OUT / "MATURED_ETA_OUTCOME_AUDIT.json").write_text(json.dumps(summary, indent=2) + "\n")
    (OUT / "MATURED_ETA_OUTCOME_AUDIT.md").write_text("# Matured ETA outcome audit\n\n" + f"Matched rows: {len(rows)}; distinct visit proxies: {len(groups)}; distinct vessels: {summary['distinct_vessels']}; prediction dates: {summary['prediction_dates']}.\n\n" + f"All matched rows satisfy retrieval/source chronology: {summary['all_matched_rows_have_retrieval_chronology']}.\n\n" + "The CSV is a deterministic 12-row audit sample: all repeated-visit proxies plus the largest remaining absolute-error cases. It preserves source manifests/raw references and is not a new label source.\n\n" + "Signed error convention: reported arrival minus published ETA; positive means reported later than ETA.\n\n" + f"CDG-40 comparison: {summary['cdg40_comparison']}.\n\n" + "Do not use the two-date cohort for temporal holdout or ML training.\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()

