#!/usr/bin/env python3
"""Repeatable, leakage-aware maturation of prospective MPA ETA candidates.

The script writes only derived files.  A missing reported arrival is never a
negative outcome unless reported-arrival coverage is known to extend past the
entire plausible source-time bound.
"""
import csv, hashlib, json, os
from datetime import datetime, timezone
from pathlib import Path

from cdg201_time import mpa_representations, parse_mpa_source, parse_retrieval_utc


def valid_imo(value):
    value = str(value or "")
    return len(value) == 7 and value.isdigit() and sum(int(value[i]) * (7-i) for i in range(6)) % 10 == int(value[6])


def iso(value):
    try: return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError): return None


def valid_arrivals(root):
    rows = []
    for manifest_path in root.glob("arrivals*.manifest.json"):
        try: manifest = json.loads(manifest_path.read_text())
        except Exception: continue
        raw_name = manifest.get("raw_file") or ""; raw_path = root / raw_name
        if manifest.get("error") or not raw_name or not raw_path.exists(): continue
        payload = raw_path.read_bytes()
        if hashlib.sha256(payload).hexdigest() != manifest.get("sha256"): continue
        try: data = json.loads(payload)
        except Exception: continue
        if not isinstance(data, list): continue
        for record in data:
            vessel = record.get("vesselParticulars") or {}; imo = str(vessel.get("imoNumber") or "")
            if valid_imo(imo) and record.get("arrivedTime"):
                rows.append({"imo":imo,"vessel_name":vessel.get("vesselName") or "","call_sign":vessel.get("callSign") or "",
                             "arrived_time":record["arrivedTime"],"location_from":record.get("locationFrom") or "",
                             "location_to":record.get("locationTo") or "","collection_utc":manifest.get("retrieval_started_utc") or "",
                             "arrived_time_sgt":mpa_representations(record["arrivedTime"])["sgt"],"arrived_time_utc":mpa_representations(record["arrivedTime"])["utc"],
                             "manifest":manifest_path.name,"raw_file":raw_name,"queried_date":manifest.get("queried_date") or ""})
    # Different snapshots observe the same reported event; retain earliest
    # valid observation as provenance, not as an additional outcome.
    unique = {}
    for row in rows:
        key = (row["imo"],row["arrived_time"],row["location_from"],row["location_to"])
        if key not in unique or row["collection_utc"] < unique[key]["collection_utc"]: unique[key] = row
    return list(unique.values())


def classify_candidate(eta, actuals):
    """Return a label status, optional arrival, and counts; never manufacture a negative."""
    collected = parse_retrieval_utc(eta["latest_collection_utc"]); expected = parse_mpa_source(eta["revised_expected"])
    if not collected or not expected or not valid_imo(eta.get("imo")):
        return "invalid", {}, 0, 0, False, "invalid_eta_identity_or_timestamp"
    candidates = [a for a in actuals if a["imo"] == eta["imo"] and a["location_to"] == eta["location_to"] and parse_retrieval_utc(a["collection_utc"]) and parse_retrieval_utc(a["collection_utc"]) > collected]
    near = [a for a in candidates if (actual_time := parse_mpa_source(a["arrived_time"])) and abs((actual_time - expected).total_seconds()) <= 72*3600]
    exact = [a for a in near if a["location_from"] == eta["location_from"] and (not eta["call_sign"] or a["call_sign"] == eta["call_sign"])]
    # Retrieval after cutoff establishes that the outcome was not available as
    # a feature.  It does not establish that the physical/source arrival event
    # happened after cutoff.  An event at or before cutoff cannot be this
    # prediction's future label; it may be a prior visit with the same context.
    post_cutoff_exact = [a for a in exact if (actual_time := parse_mpa_source(a["arrived_time"])) and actual_time > collected]
    pre_or_at_cutoff_exact = [a for a in exact if (actual_time := parse_mpa_source(a["arrived_time"])) and actual_time <= collected]
    # A later successful same-date query alone does not prove complete reported
    # coverage under unknown source timezone semantics, so it only moves a row
    # to unresolved rather than creating a no-arrival/negative label.
    coverage_seen_after_bound = bool(any(parse_retrieval_utc(a["collection_utc"]) and parse_retrieval_utc(a["collection_utc"]) > expected.astimezone(timezone.utc) for a in actuals if a["queried_date"] == eta["revised_expected"][:10]))
    if len(post_cutoff_exact) == 1 and len(near) == 1:
        return "matched", post_cutoff_exact[0], len(candidates), len(near), coverage_seen_after_bound, "single_context_compatible_later_reported_arrival"
    if pre_or_at_cutoff_exact and not post_cutoff_exact:
        return "ambiguous", pre_or_at_cutoff_exact[0], len(candidates), len(near), coverage_seen_after_bound, "context_match_not_after_prediction_cutoff"
    if len(post_cutoff_exact) > 1 or len(exact) > 1 or len(near) > 1:
        return "ambiguous", near[0] if near else {}, len(candidates), len(near), coverage_seen_after_bound, "multiple_possible_visits"
    if coverage_seen_after_bound:
        return "unresolved_due_to_coverage", {}, len(candidates), len(near), True, "reported_coverage_seen_but_not_proven_complete"
    return "pending", {}, len(candidates), len(near), False, "await_later_reported_arrival_coverage"


def main():
    base = Path(os.environ.get("CDG201_DATA_ROOT", Path.home() / "opt/data/change_detection")); root = base / "data/external/maritime/raw/mpa_oceansx"
    out = base / "analysis/ai_readiness"; out.mkdir(parents=True, exist_ok=True)
    revisions = list(csv.DictReader((base / "analysis/revisions/expected_revisions.csv").open()))
    prospective = [r for r in revisions if r.get("prospective") == "True"]
    actuals = valid_arrivals(root); output = []
    for eta in prospective:
        status, chosen, candidate_count, near_count, coverage_seen, reason = classify_candidate(eta, actuals)
        row = {"eta_imo":eta["imo"],"vessel_name":eta["vessel_name"],"eta_revised_source_time":eta["revised_expected"],
               "eta_collection_utc":eta["latest_collection_utc"],"eta_run_id":eta["latest_run_id"],
               "eta_location_from":eta["location_from"],"eta_location_to":eta["location_to"],
               "eta_previous_manifest":eta["previous_manifest"],"eta_manifest":eta["latest_manifest"],"prospectiveness":eta["prospectiveness_classification"],
               "source_timezone_assumption":"MPA source event strings are confirmed Asia/Singapore (UTC+08:00); raw strings retained with derived SGT/UTC representations.",
               "label_status":status,"exclusion_or_status_reason":reason,"candidate_count_same_imo_destination":candidate_count,"candidate_count_within_72h":near_count,
               "reported_coverage_seen_after_possible_event":coverage_seen,
               "eta_revised_sgt":eta.get("revised_expected_sgt",mpa_representations(eta["revised_expected"])["sgt"]),"eta_revised_utc":eta.get("revised_expected_utc",mpa_representations(eta["revised_expected"])["utc"]),
               "actual_time":chosen.get("arrived_time", ""),"actual_time_sgt":chosen.get("arrived_time_sgt", ""),"actual_time_utc":chosen.get("arrived_time_utc", ""),"actual_collection_utc":chosen.get("collection_utc", ""),
               "actual_location_from":chosen.get("location_from", ""),"actual_location_to":chosen.get("location_to", ""),
               "actual_manifest":chosen.get("manifest", ""),"actual_raw_file":chosen.get("raw_file", ""),
               "leakage_note":"ETA features must use only records at or before eta_collection_utc; later reported arrivals are labels only."}
        output.append(row)
    fields = list(output[0]) if output else ["eta_imo","actual_match_status"]
    with (out / "prospective_eta_actual_candidates.csv").open("w",newline="") as handle:
        writer=csv.DictWriter(handle,fieldnames=fields);writer.writeheader();writer.writerows(output)
    counts = {k:sum(r["label_status"]==k for r in output) for k in sorted({r["label_status"] for r in output})}
    matched = [row for row in output if row["label_status"] == "matched"]
    matched_vessels = {row["eta_imo"] for row in matched}
    matched_prediction_dates = {row["eta_collection_utc"][:10] for row in matched if row["eta_collection_utc"]}
    baseline_ready = bool(matched)
    model_ready = len(matched) >= 30 and len(matched_vessels) >= 10 and len(matched_prediction_dates) >= 3
    report = ["# MPA ETA ground-truth readiness", "", f"Prospective ETA revisions: {len(prospective)}.",
              f"Unique checksum-valid reported-arrival events examined: {len(actuals)}.",
              f"Outcome classification: {counts}.", "",
              "A confident match requires valid IMO, matching call sign where available, exact origin/destination, a later reported-arrival collection, a single candidate within 72 hours in the source timestamp domain, and source evidence references.",
              "The 72-hour window is an identity-screening bound, not an ETA target or an assumed dwell time. MPA source event timestamps are interpreted as Asia/Singapore; raw source strings remain preserved.",
              "No feature may use raw snapshots or reported-arrival records collected after `eta_collection_utc`. A missing actual is censored; even later reported coverage is unresolved unless its completeness can be established.",
              f"Matched rows/vessels/prediction dates: {len(matched)}/{len(matched_vessels)}/{len(matched_prediction_dates)}.",
              "ETA-persistence may be evaluated only on the matched rows. Do not train an ML model until independent temporal coverage reaches the stated minimum."]
    (out / "MPA_ETA_GROUND_TRUTH_READINESS.md").write_text("\n".join(report)+"\n")
    monitor = {"generated_utc":datetime.now(timezone.utc).isoformat(),"prospective_eta_candidates":len(prospective),"valid_reported_arrival_events":len(actuals),"label_status_counts":counts,
               "leakage_safe_eta_persistence_examples":counts.get("matched",0),"minimum_readiness":{"minimum_matched_examples":30,"minimum_distinct_vessels":10,"minimum_distinct_prediction_dates":3},
               "matched_distinct_vessels":len(matched_vessels),"matched_prediction_dates":len(matched_prediction_dates),
               "ready_for_eta_persistence_evaluation":baseline_ready,"ready_for_ml_evaluation":model_ready,
               "reason":"Persistence requires matched labels; ML additionally requires at least 30 rows, 10 vessels, and 3 prediction dates."}
    (out / "AI_READINESS_MONITOR.json").write_text(json.dumps(monitor,indent=2)+"\n")
    print(json.dumps({"prospective_eta_revisions":len(prospective),"valid_actual_events":len(actuals),"outcome_counts":counts,"monitor":str(out / "AI_READINESS_MONITOR.json")}))

if __name__ == "__main__": main()
