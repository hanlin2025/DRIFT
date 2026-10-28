#!/usr/bin/env python3
"""Offline integrity and observed-cadence report for declaration manifests."""
import argparse, hashlib, json
from collections import Counter
from datetime import datetime
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--raw', required=True); parser.add_argument('--output', required=True); args=parser.parse_args()
    entries=[]
    for path in Path(args.raw).glob('*.manifest.json'):
        try: m=json.loads(path.read_text())
        except json.JSONDecodeError: continue
        raw=path.parent/m.get('raw_file',''); checksum_ok=bool(m.get('raw_file') and raw.exists() and m.get('sha256') and hashlib.sha256(raw.read_bytes()).hexdigest()==m['sha256'])
        entries.append({**m, 'manifest_file':path.name, 'checksum_ok':checksum_ok})
    entries.sort(key=lambda x:x.get('retrieval_started_utc',''))
    successful=[x for x in entries if x.get('valid_coverage') and x['checksum_ok']]
    runs=Counter(x.get('collection_run_id') for x in entries); first=successful[0]['retrieval_started_utc'] if successful else None; last=successful[-1]['retrieval_started_utc'] if successful else None
    report={'request_manifests':len(entries),'successful_checksum_valid':len(successful),'failed_or_missing_coverage':len(entries)-len(successful),'empty_valid_results':sum(x.get('empty_valid_result',False) for x in entries),'first_success_utc':first,'last_success_utc':last,'distinct_collection_runs':len(runs),'coverage_note':'Observed intervals begin at the first successful prospective collection; missing historical snapshots are not inferred.'}
    output=Path(args.output); output.parent.mkdir(parents=True,exist_ok=True); output.write_text(json.dumps(report,indent=2)+'\n'); print(json.dumps(report))

if __name__=='__main__': main()
