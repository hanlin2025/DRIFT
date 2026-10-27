#!/usr/bin/env python3
"""Run-aware, offline ETA/ETD revision detection for MPA expected snapshots.

Each endpoint/date response is validated separately. A failed departure request
therefore does not suppress valid arrival-only comparisons. Raw files are read
only and older manifests without an HTTP status remain explicitly legacy.
"""
import argparse
import csv
import hashlib
import json
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

DATASETS = ("due_to_arrive", "due_to_depart")
# Adjacent snapshots are expected roughly hourly. A six-hour bound is
# deliberately conservative: larger timestamp differences lack enough context
# to distinguish a genuine reschedule from another visit by the same vessel.
MAX_CONTINUITY_HOURS = 3


def valid_imo(value):
    s = str(value or "")
    return len(s) == 7 and s.isdigit() and sum(int(s[i]) * (7 - i) for i in range(6)) % 10 == int(s[6])


def parse_time(value):
    """Used only for ordering. MPA source timestamp timezone is still unknown."""
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def is_prospective(collection_utc, expected_time):
    """Only compare when both timestamps have compatible timezone awareness."""
    collected = parse_time(collection_utc)
    if not collected or not expected_time or (collected.tzinfo is None) != (expected_time.tzinfo is None):
        return False
    return collected < expected_time


def prospectiveness(expected, collection_times):
    """Conservative status without assigning an undocumented source timezone.

    For an offset-less source timestamp, UTC−12 through UTC+14 covers every
    current civil UTC offset. Only a result that holds over that whole interval
    is called prospective or retrospective; all other cases remain ambiguous.
    """
    stamp = parse_time(expected)
    collected = [parse_time(x) for x in collection_times]
    if not stamp or not all(collected):
        return "insufficient_timestamp_evidence", "", "", False
    if stamp.tzinfo is not None:
        value = all(x < stamp for x in collected)
        return ("prospective_documented_offset" if value else "retrospective_documented_offset",
                stamp.astimezone(timezone.utc).isoformat(), stamp.astimezone(timezone.utc).isoformat(), value)
    early = (stamp.replace(tzinfo=timezone.utc) - timedelta(hours=14))
    late = (stamp.replace(tzinfo=timezone.utc) + timedelta(hours=12))
    if all(x < early for x in collected):
        return "prospective_under_all_utc_minus12_to_plus14_offsets", early.isoformat(), late.isoformat(), True
    if any(x >= late for x in collected):
        return "retrospective_under_all_utc_minus12_to_plus14_offsets", early.isoformat(), late.isoformat(), False
    return "timezone_ambiguous", early.isoformat(), late.isoformat(), False


def validate_manifest(root, path):
    try:
        meta = json.loads(path.read_text())
    except Exception as exc:
        return {"manifest": path.name, "valid": False, "reason": f"manifest_json:{type(exc).__name__}"}
    if meta.get("dataset") not in DATASETS:
        return None
    status = meta.get("http_status")
    base = {**meta, "manifest": path.name, "http_status_effective": status}
    if meta.get("error"):
        return {**base, "valid": False, "schema_valid": False, "checksum_valid": None, "reason": meta["error"]}
    raw_name = meta.get("raw_file") or ""
    raw = root / raw_name
    if not raw_name or not raw.exists():
        return {**base, "valid": False, "schema_valid": False, "checksum_valid": False, "reason": "missing_raw_file"}
    payload = raw.read_bytes()
    checksum_ok = hashlib.sha256(payload).hexdigest() == meta.get("sha256")
    if not checksum_ok:
        return {**base, "valid": False, "schema_valid": False, "checksum_valid": False, "reason": "checksum_mismatch"}
    try:
        records = json.loads(payload)
    except Exception as exc:
        return {**base, "valid": False, "schema_valid": False, "checksum_valid": True, "reason": f"raw_json:{type(exc).__name__}"}
    if status is not None and not 200 <= int(status) < 300:
        return {**base, "valid": False, "schema_valid": isinstance(records, list), "checksum_valid": True, "reason": f"http_status:{status}"}
    if not isinstance(records, list):
        return {**base, "valid": False, "schema_valid": False, "checksum_valid": True, "reason": "schema_not_array"}
    return {**base, "valid": True, "schema_valid": True, "checksum_valid": True, "reason": "", "records": records}


def load(root):
    slices, observations = [], []
    for path in sorted(root.glob("*.manifest.json")):
        item = validate_manifest(root, path)
        if not item:
            continue
        item["run_id"] = item.get("collection_run_id") or ""
        slices.append(item)
        if not item["valid"]:
            continue
        for record in item["records"]:
            particulars = record.get("vesselParticulars") or {}
            # The two feeds use different documented field spellings/casing.
            expected = record.get("duetoArriveTime") or record.get("dueToDepart")
            imo = str(particulars.get("imoNumber") or "")
            if valid_imo(imo) and expected:
                observations.append({
                    "dataset": item["dataset"], "imo": imo, "vessel_name": particulars.get("vesselName") or "",
                    "call_sign": particulars.get("callSign") or "",
                    "from": record.get("locationFrom") or "", "to": record.get("locationTo") or "",
                    "expected": expected, "queried_date": item.get("queried_date") or "",
                    "retrieval_utc": item.get("retrieval_started_utc") or "", "raw_file": item.get("raw_file") or "",
                    "manifest": item["manifest"], "run_id": item["run_id"],
                })
    return slices, observations


def run_coverage(slices):
    grouped = defaultdict(list)
    for item in slices:
        if item["run_id"]:
            grouped[item["run_id"]].append(item)
    results = {}
    for run_id, items in grouped.items():
        feeds = {}
        for dataset in DATASETS:
            subset = [x for x in items if x.get("dataset") == dataset]
            feeds[dataset] = {
                "request_count": len(subset), "valid_count": sum(x["valid"] for x in subset),
                "invalid_count": sum(not x["valid"] for x in subset),
                "valid_dates": sorted(x.get("queried_date", "") for x in subset if x["valid"]),
                "missing_dates": sorted(x.get("queried_date", "") for x in subset if not x["valid"]),
            }
        times = [x.get("retrieval_started_utc") for x in items if x.get("retrieval_started_utc")]
        results[run_id] = {"kind": "explicit_run_id", "first_utc": min(times) if times else "",
                           "last_utc": max(times) if times else "", "datasets": feeds}
    return results


def dedupe_run(rows):
    """Collapse only duplicated representations of the exact same movement in one run."""
    result = {}
    for row in rows:
        result.setdefault((row["dataset"], row["imo"], row["from"], row["to"], row["expected"]), row)
    return list(result.values())


def match_runs(previous, latest, coverage):
    """One-to-one same-context matching; tie candidates are retained as ambiguity."""
    revisions, ambiguous, additions, insufficient = [], [], [], []
    used = set()
    for old in dedupe_run(previous):
        if not coverage.get((old["run_id"], old["dataset"], old["queried_date"]), False):
            insufficient.append({"reason": "previous_slice_invalid", **old}); continue
        old_time = parse_time(old["expected"])
        if not old_time:
            insufficient.append({"reason": "unparseable_source_time", **old}); continue
        candidates = []
        for index, new in enumerate(dedupe_run(latest)):
            if index in used or (new["dataset"], new["imo"], new["from"], new["to"]) != (old["dataset"], old["imo"], old["from"], old["to"]):
                continue
            new_time = parse_time(new["expected"])
            if new_time is None:
                continue
            distance = abs((new_time - old_time).total_seconds()) / 3600
            if distance <= MAX_CONTINUITY_HOURS:
                candidates.append((distance, index, new))
        if not candidates:
            insufficient.append({"reason": "no_defensible_successor_or_out_of_coverage", **old}); continue
        candidates.sort(key=lambda value: value[0])
        if len(candidates) > 1 and candidates[0][0] == candidates[1][0]:
            ambiguous.append({"reason": "equidistant_repeated_visit_candidates", **old}); continue
        _, index, new = candidates[0]
        used.add(index)
        # A calendar-date move can otherwise be an old visit disappearing and a
        # different visit appearing. Require both date slices in both runs.
        if old["queried_date"] != new["queried_date"]:
            required = ((old["run_id"], old["dataset"], old["queried_date"]),
                        (old["run_id"], old["dataset"], new["queried_date"]),
                        (new["run_id"], new["dataset"], old["queried_date"]),
                        (new["run_id"], new["dataset"], new["queried_date"]))
            if not all(coverage.get(key, False) for key in required):
                insufficient.append({"reason": "cross_date_coverage_incomplete", **old})
                continue
        if old["expected"] != new["expected"]:
            old_time, new_time = parse_time(old["expected"]), parse_time(new["expected"])
            has_location_context = bool(old["from"] or old["to"]) and bool(new["from"] or new["to"])
            prospectiveness_class, early_bound, late_bound, prospective = prospectiveness(
                new["expected"], (old["retrieval_utc"], new["retrieval_utc"]))
            revisions.append({
                "kind": "confirmed_observed_revision" if has_location_context else "observed_timestamp_change_low_context",
                "dataset": old["dataset"], "imo": old["imo"], "vessel_name": old["vessel_name"],
                "call_sign": old["call_sign"], "location_from": old["from"], "location_to": old["to"],
                "previous_expected": old["expected"], "revised_expected": new["expected"],
                "revision_minutes": int((new_time - old_time).total_seconds() / 60),
                "previous_collection_utc": old["retrieval_utc"], "latest_collection_utc": new["retrieval_utc"],
                "previous_run_id": old["run_id"], "latest_run_id": new["run_id"],
                "previous_manifest": old["manifest"], "latest_manifest": new["manifest"],
                "previous_raw_file": old["raw_file"], "latest_raw_file": new["raw_file"],
                "confidence": "conservative_context_time_continuity" if has_location_context else "low_imo_callsign_time_only",
                "source_timezone": "unknown", "prospectiveness_classification": prospectiveness_class,
                "source_timestamp_utc_earliest_bound": early_bound, "source_timestamp_utc_latest_bound": late_bound,
                "prospective": prospective,
            })
    for index, new in enumerate(dedupe_run(latest)):
        if index not in used:
            additions.append({"kind": "newly_observed_expected_movement", **new})
    return revisions, ambiguous, additions, insufficient


def analyse(slices, observations):
    cycles = run_coverage(slices)
    rows_by_run = defaultdict(list)
    for row in observations:
        if row["run_id"]:
            rows_by_run[row["run_id"]].append(row)
    valid_slices = {(x["run_id"], x.get("dataset"), x.get("queried_date")): x["valid"] for x in slices if x["run_id"]}
    ordered = sorted(cycles, key=lambda run: cycles[run]["first_utc"])
    output = ([], [], [], [])
    for prior, later in zip(ordered, ordered[1:]):
        for dataset in DATASETS:
            old = [x for x in rows_by_run[prior] if x["dataset"] == dataset]
            new = [x for x in rows_by_run[later] if x["dataset"] == dataset]
            if old and new:
                matched = match_runs(old, new, valid_slices)
                output = tuple(a + b for a, b in zip(output, matched))
    return cycles, *output


def write_csv(path, rows):
    fields = sorted({field for row in rows for field in row}) if rows else ["kind"]
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader(); writer.writerows(rows)


def request_audit_rows(slices):
    """Manifest-level audit, retaining legacy HTTP-status uncertainty."""
    rows = []
    for item in slices:
        if not item["run_id"]:
            continue
        rows.append({
            "collection_run_id": item["run_id"], "dataset": item.get("dataset", ""),
            "queried_date": item.get("queried_date", ""), "retrieval_started_utc": item.get("retrieval_started_utc", ""),
            "http_status": item.get("http_status_effective") if item.get("http_status_effective") is not None else "not_recorded_legacy_manifest",
            "schema_valid": item.get("schema_valid"), "checksum_valid": item.get("checksum_valid"),
            "response_record_count": len(item.get("records", [])) if item.get("valid") else "",
            "coverage_valid": item.get("valid"), "error_or_validation_reason": item.get("reason", ""),
            "manifest": item.get("manifest", ""), "raw_file": item.get("raw_file", ""),
        })
    return sorted(rows, key=lambda row: (row["collection_run_id"], row["retrieval_started_utc"]))


def normalization_audit(slices, observations):
    """Explain valid-IMO/timestamp filtering and within-run deduplication."""
    results = []
    for run_id in sorted({x["run_id"] for x in slices if x["run_id"]}):
        for dataset in DATASETS:
            subset = [x for x in slices if x["run_id"] == run_id and x.get("dataset") == dataset and x["valid"]]
            source = [record for item in subset for record in item["records"]]
            timestamp_key = "duetoArriveTime" if dataset == "due_to_arrive" else "dueToDepart"
            invalid_imo = sum(not valid_imo((r.get("vesselParticulars") or {}).get("imoNumber")) for r in source)
            missing_time = sum(valid_imo((r.get("vesselParticulars") or {}).get("imoNumber")) and not r.get(timestamp_key) for r in source)
            normalized = [x for x in observations if x["run_id"] == run_id and x["dataset"] == dataset]
            results.append({"collection_run_id": run_id, "dataset": dataset, "valid_source_records": len(source),
                            "invalid_imo": invalid_imo, "missing_expected_timestamp": missing_time,
                            "normalized_before_deduplication": len(normalized),
                            "within_run_duplicate_representations": len(normalized) - len(dedupe_run(normalized)),
                            "normalized_after_deduplication": len(dedupe_run(normalized)),
                            "invalid_or_missing_date_slices": ";".join(sorted(x.get("queried_date", "") for x in slices if x["run_id"] == run_id and x.get("dataset") == dataset and not x["valid"]))})
    return results


def write_evidence_report(path, confirmed, low_context):
    lines = ["# MPA observed expected-arrival revision evidence", "",
             "These are published timestamp changes seen in distinct collection runs, not verified arrivals or operational delay predictions.",
             "Source timestamps have no documented timezone. Prospectiveness is only positive where the timestamp is future under every civil UTC offset from UTC−12 through UTC+14.", "",
             "## Confirmed arrival-context changes", ""]
    if not confirmed:
        lines.append("No confirmed arrival-context changes.")
    for row in confirmed:
        direction = "later" if int(row["revision_minutes"]) > 0 else "earlier"
        lines.extend([f"### {row['vessel_name'] or 'Unnamed vessel'} — IMO {row['imo']}",
                      f"- Expected arrival: `{row['previous_expected']}` → `{row['revised_expected']}` ({abs(int(row['revision_minutes']))} minutes {direction}).",
                      f"- Collected: `{row['previous_collection_utc']}` → `{row['latest_collection_utc']}` UTC.",
                      f"- Runs: `{row['previous_run_id']}` → `{row['latest_run_id']}`.",
                      f"- Context: `{row['location_from']}` → `{row['location_to']}`; confidence: `{row['confidence']}`.",
                      f"- Prospectiveness: `{row['prospectiveness_classification']}`; plausible UTC bound for revised source time: `{row['source_timestamp_utc_earliest_bound']}` to `{row['source_timestamp_utc_latest_bound']}`.",
                      f"- Evidence: `{row['previous_manifest']}` / `{row['latest_manifest']}`.", ""])
    lines.extend(["## Separate low-context departure changes", "",
                  f"{len(low_context)} timestamp changes were detected using IMO/call-sign/time continuity only. The due-departure feed has no origin/destination context in these records, so they are not asserted as same-movement revisions.",
                  ""])
    for row in low_context:
        lines.append(f"- {row['vessel_name'] or 'Unnamed vessel'} (IMO {row['imo']}): `{row['previous_expected']}` → `{row['revised_expected']}`; `{row['prospectiveness_classification']}`; manifests `{row['previous_manifest']}` / `{row['latest_manifest']}`.")
    path.write_text("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path, help="MPA raw snapshot/manifest directory")
    parser.add_argument("--output", required=True, type=Path, help="Derived revision-report directory")
    args = parser.parse_args()
    root = args.root
    output = args.output; output.mkdir(parents=True, exist_ok=True)
    slices, observations = load(root)
    cycles, revisions, ambiguous, additions, insufficient = analyse(slices, observations)
    confirmed = [row for row in revisions if row["kind"] == "confirmed_observed_revision"]
    low_context = [row for row in revisions if row["kind"] != "confirmed_observed_revision"]
    write_csv(output / "expected_revisions.csv", confirmed)
    write_csv(output / "low_context_timestamp_changes.csv", low_context)
    write_csv(output / "ambiguous_revision_candidates.csv", ambiguous)
    write_csv(output / "newly_observed_expected_movements.csv", additions)
    write_csv(output / "insufficient_evidence.csv", insufficient)
    write_csv(output / "explicit_run_request_audit.csv", request_audit_rows(slices))
    audit = normalization_audit(slices, observations)
    write_csv(output / "normalization_audit.csv", audit)
    write_evidence_report(output / "MPA_REVISION_EVIDENCE_REPORT.md", confirmed, low_context)
    (output / "collection_cycles.json").write_text(json.dumps(cycles, indent=2) + "\n")
    complete = sum(all(feed["request_count"] and not feed["invalid_count"] for feed in c["datasets"].values()) for c in cycles.values())
    prospective = sum(row["prospective"] for row in confirmed)
    report = ["# Expected ETA/ETD revision report", "",
              f"Explicit collection cycles: {len(cycles)}; complete cycles: {complete}; partially valid cycles: {len(cycles)-complete}.",
              f"Valid normalized observations: {len(observations)}.",
              f"Valid explicit-run arrival movement observations: {sum(len(dedupe_run([x for x in observations if x['run_id']==r and x['dataset']=='due_to_arrive'])) for r in cycles)}.",
              f"Valid explicit-run departure movement observations: {sum(len(dedupe_run([x for x in observations if x['run_id']==r and x['dataset']=='due_to_depart'])) for r in cycles)}.",
              f"Confirmed observed revisions: {len(confirmed)}; low-context timestamp changes: {len(low_context)}; ambiguous candidates: {len(ambiguous)}; additions: {len(additions)}; insufficient-evidence cases: {len(insufficient)}.",
              f"Prospective revision evidence: {prospective}.", "", "## Explicit-cycle coverage", ""]
    for run_id, cycle in sorted(cycles.items(), key=lambda item: item[1]["first_utc"]):
        arrival, departure = cycle["datasets"]["due_to_arrive"], cycle["datasets"]["due_to_depart"]
        report.append(f"- `{run_id}` — {cycle['first_utc']} to {cycle['last_utc']}; arrivals {arrival['valid_count']}/{arrival['request_count']} valid; departures {departure['valid_count']}/{departure['request_count']} valid (missing: {', '.join(departure['missing_dates']) or 'none'}).")
    report.extend(["", "## Normalization reconciliation", ""])
    for row in audit:
        report.append(f"- `{row['collection_run_id']}` {row['dataset']}: {row['valid_source_records']} valid-schema source records; {row['invalid_imo']} invalid IMOs; {row['missing_expected_timestamp']} missing expected timestamps; {row['within_run_duplicate_representations']} duplicate representations; {row['normalized_after_deduplication']} normalized observations.")
    report.extend(["", "Valid empty arrays are successful coverage. Failed/schema-invalid slices are missing coverage, never zero movements. The historical 3,816-candidate diagnostic is quarantined and unused.",
              "MPA source expected timestamp timezone is undocumented; collection timestamps are UTC. A revision is prospective only where both collections precede the expected timestamp."])
    (output / "REAL_DATA_REPORT.md").write_text("\n".join(report) + "\n")
    print(json.dumps({"explicit_cycles":len(cycles),"complete_cycles":complete,"partial_cycles":len(cycles)-complete,"validated_observations":len(observations),"confirmed_revisions":len(confirmed),"low_context_changes":len(low_context),"ambiguous":len(ambiguous),"insufficient":len(insufficient)}))


if __name__ == "__main__":
    main()
