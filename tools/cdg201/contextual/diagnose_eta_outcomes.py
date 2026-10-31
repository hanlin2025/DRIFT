#!/usr/bin/env python3
"""Bounded offline diagnostic for unmatched prospective ETA outcomes.

This is an explanation layer over the maturation data. It does not relabel an
unmatched ETA as negative, failed, or delayed.
"""
import csv
import hashlib
import json
import os
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

from build_eta_ground_truth_candidates import valid_arrivals
from cdg201_time import parse_mpa_source, parse_retrieval_utc


def parse(value):
    try: return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError): return None


def valid_arrival_slices(root):
    rows = []
    for path in root.glob("arrivals*.manifest.json"):
        try: meta = json.loads(path.read_text())
        except Exception: continue
        raw = root / (meta.get("raw_file") or "")
        if meta.get("error") or not raw.exists() or not meta.get("sha256") or hashlib.sha256(raw.read_bytes()).hexdigest() != meta["sha256"]:
            continue
        try: payload = json.loads(raw.read_text())
        except Exception: continue
        if isinstance(payload, list): rows.append(meta)
    return rows


def group_visits(rows):
    """Tentative grouping avoids calling repeated snapshots independent visits."""
    grouped = defaultdict(list)
    for row in rows:
        grouped[(row["imo"], row["location_from"], row["location_to"], row.get("call_sign", ""))].append(row)
    for context, values in grouped.items():
        values.sort(key=lambda row: row["revised_expected"])
        number, prior = 0, None
        for row in values:
            stamp = parse_mpa_source(row["revised_expected"])
            if prior is None or not stamp or (stamp - prior).total_seconds() > 6 * 3600:
                number += 1
            row["tentative_visit_group"] = f"{context[0]}:{context[1]}:{context[2]}:{number}"
            prior = stamp
    return rows


def main():
    base = Path(os.environ.get("CDG201_DATA_ROOT", Path.home() / "opt/data/change_detection"))
    root = base / "data/external/maritime/raw/mpa_oceansx"
    output = base / "analysis/ai_readiness"; output.mkdir(parents=True, exist_ok=True)
    revisions = list(csv.DictReader((base / "analysis/revisions/expected_revisions.csv").open()))
    prospective = group_visits([dict(row) for row in revisions if row.get("prospective") == "True"])
    actuals, slices = valid_arrivals(root), valid_arrival_slices(root)
    latest_actual_collection = max((parse_retrieval_utc(x["retrieval_started_utc"]) for x in slices if parse_retrieval_utc(x.get("retrieval_started_utc"))), default=None)
    diagnostic = []
    for eta in prospective:
        cutoff, expected = parse_retrieval_utc(eta.get("latest_collection_utc")), parse_mpa_source(eta.get("revised_expected"))
        same_imo = [a for a in actuals if a["imo"] == eta["imo"] and parse_retrieval_utc(a["collection_utc"]) and cutoff and parse_retrieval_utc(a["collection_utc"]) > cutoff]
        same_destination = [a for a in same_imo if a["location_to"] == eta["location_to"]]
        near = [a for a in same_destination if expected and (actual_time := parse_mpa_source(a["arrived_time"])) and abs((actual_time - expected).total_seconds()) <= 72 * 3600]
        exact = [a for a in near if a["location_from"] == eta["location_from"] and (not eta.get("call_sign") or a["call_sign"] == eta["call_sign"])]
        future_exact = [a for a in exact if (actual_time := parse_mpa_source(a["arrived_time"])) and cutoff and actual_time > cutoff]
        already_occurred_exact = [a for a in exact if (actual_time := parse_mpa_source(a["arrived_time"])) and cutoff and actual_time <= cutoff]
        source_dates = [expected.date().isoformat()] if expected else []
        relevant_slices = [s for s in slices if s.get("queried_date") in source_dates and cutoff and parse_retrieval_utc(s.get("retrieval_started_utc")) and parse_retrieval_utc(s["retrieval_started_utc"]) > cutoff]
        definitely_future = bool(latest_actual_collection and expected and latest_actual_collection < expected.astimezone(timezone.utc))
        if len(future_exact) == 1 and len(near) == 1:
            reason, status = "single_context_match_available", "match_available_recheck_maturation"
        elif already_occurred_exact and not future_exact:
            reason, status = "context_match_not_after_prediction_cutoff", "ambiguous_source_event_timing"
        elif len(exact) > 1 or len(near) > 1:
            reason, status = "multiple_possible_visits", "ambiguous"
        elif latest_actual_collection is None or expected is None:
            reason, status = "insufficient_collection_or_source_time_bounds", "timestamp_or_coverage_unresolved"
        elif definitely_future:
            reason, status = "expected_time_definitely_after_latest_actual_collection", "awaiting_future_outcome"
        elif not relevant_slices:
            reason, status = "no_successful_reported_arrival_slice_after_cutoff_for_relevant_dates", "coverage_unresolved"
        elif not same_imo:
            reason, status = "reported_arrivals_covered_but_no_same_imo_observation", "unresolved_no_same_vessel_observation"
        elif not same_destination:
            reason, status = "same_imo_observed_but_destination_context_differs", "unresolved_context_mismatch"
        else:
            reason, status = "same_vessel_observed_but_no_single_context_compatible_arrival", "unresolved_matching_constraints"
        diagnostic.append({
            "tentative_visit_group": eta["tentative_visit_group"], "imo":eta["imo"], "vessel_name":eta.get("vessel_name", ""),
            "call_sign":eta.get("call_sign", ""), "location_from":eta["location_from"], "location_to":eta["location_to"],
            "prediction_cutoff_utc":eta["latest_collection_utc"], "expected_arrival_source_string":eta["revised_expected"],
            "source_timezone_classification":"confirmed_Asia/Singapore", "expected_sgt":expected.isoformat() if expected else "", "expected_utc":expected.astimezone(timezone.utc).isoformat() if expected else "",
            "latest_successful_actual_collection_utc":latest_actual_collection.isoformat() if latest_actual_collection else "",
            "relevant_actual_slice_count_after_cutoff":len(relevant_slices), "same_imo_actual_count":len(same_imo), "same_destination_actual_count":len(same_destination),
            "within_72h_actual_count":len(near), "exact_context_actual_count":len(exact),
            "diagnostic_status":status, "reason":reason,
            "eta_manifest":eta["latest_manifest"], "eta_run_id":eta["latest_run_id"],
            "possible_actual_manifests":";".join(a["manifest"] for a in exact or near),
            "leakage_note":"Reported arrivals are inspected only as later outcome evidence; never prediction features."})
    fields = list(diagnostic[0]) if diagnostic else ["imo", "diagnostic_status"]
    with (output / "prospective_eta_outcome_diagnostic.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields); writer.writeheader(); writer.writerows(diagnostic)
    status_counts, reason_counts = Counter(r["diagnostic_status"] for r in diagnostic), Counter(r["reason"] for r in diagnostic)
    report = {"candidate_rows":len(diagnostic), "tentative_underlying_visits":len({r["tentative_visit_group"] for r in diagnostic}),
              "latest_successful_reported_arrival_collection_utc":latest_actual_collection.isoformat() if latest_actual_collection else None,
              "status_counts":status_counts, "reason_counts":reason_counts,
              "conclusion":"No forced matches. Results distinguish outcomes genuinely in the future from coverage and context constraints under the confirmed SGT source-time convention."}
    (output / "ETA_OUTCOME_DIAGNOSTIC_REPORT.json").write_text(json.dumps(report, indent=2, default=dict) + "\n")
    lines = ["# Prospective ETA outcome diagnostic", "", f"Candidate snapshots: {len(diagnostic)}; tentative underlying visits: {report['tentative_underlying_visits']}.", f"Latest successful reported-arrival collection: `{report['latest_successful_reported_arrival_collection_utc']}`.", "", f"Status counts: {dict(status_counts)}.", f"Reasons: {dict(reason_counts)}.", "", "No unmatched candidate was treated as a negative outcome. MPA source event strings are interpreted as SGT; reported-arrival coverage is assessed separately from matching identity."]
    (output / "ETA_OUTCOME_DIAGNOSTIC_REPORT.md").write_text("\n".join(lines) + "\n")
    print(json.dumps(report, default=dict))


if __name__ == "__main__": main()
