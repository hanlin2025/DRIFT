#!/usr/bin/env python3
"""Offline, conservative comparison of append-only MPA declaration snapshots."""
import argparse, hashlib, json
from collections import defaultdict
from pathlib import Path

def checksum_payload(manifest_path):
    m=json.loads(manifest_path.read_text()); raw=manifest_path.parent/m.get('raw_file','')
    if not m.get('valid_coverage') or not raw.exists() or hashlib.sha256(raw.read_bytes()).hexdigest()!=m.get('sha256'): return m, None
    try: data=json.loads(raw.read_bytes())
    except (json.JSONDecodeError,UnicodeDecodeError): return m,None
    return m, data if isinstance(data,list) else None

def record_hash(record): return hashlib.sha256(json.dumps(record,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def candidate_key(dataset, record):
    p=record.get('vesselParticulars') if isinstance(record,dict) and isinstance(record.get('vesselParticulars'),dict) else {}
    imo=str(p.get('imoNumber') or '').strip(); call=str(p.get('callSign') or '').strip(); name=str(p.get('vesselName') or '').strip()
    context=str(record.get('location') if dataset=='arrival_declaration' else record.get('nextPort') or '').strip()
    return '|'.join((dataset,imo,call,name,context)) if imo else ''

def main():
    p=argparse.ArgumentParser();p.add_argument('--raw',required=True);p.add_argument('--output',required=True);a=p.parse_args(); groups=defaultdict(list)
    for path in Path(a.raw).glob('*.manifest.json'):
        try:m,data=checksum_payload(path)
        except json.JSONDecodeError:continue
        if data is not None: groups[(m.get('dataset'),m.get('queried_date'))].append((m,data,path.name))
    results=[]
    for group, snapshots in groups.items():
        snapshots.sort(key=lambda x:x[0].get('retrieval_started_utc',''))
        for (before,old,_),(after,new,_) in zip(snapshots,snapshots[1:]):
            old_hash={record_hash(x):x for x in old}; new_hash={record_hash(x):x for x in new}
            old_keys=defaultdict(list);new_keys=defaultdict(list)
            for x in old: old_keys[candidate_key(before['dataset'],x)].append(x)
            for x in new: new_keys[candidate_key(after['dataset'],x)].append(x)
            changed=[]; ambiguous=[]
            for key in set(old_keys)&set(new_keys):
                if not key: continue
                if len(old_keys[key])==len(new_keys[key])==1 and record_hash(old_keys[key][0])!=record_hash(new_keys[key][0]): changed.append({'candidate_identity':key,'before':old_keys[key][0],'after':new_keys[key][0]})
                elif any(record_hash(x) not in new_hash for x in old_keys[key]) or any(record_hash(x) not in old_hash for x in new_keys[key]): ambiguous.append({'candidate_identity':key,'before_count':len(old_keys[key]),'after_count':len(new_keys[key])})
            results.append({'dataset':group[0],'queried_date':group[1],'before_manifest':before.get('raw_file'),'after_manifest':after.get('raw_file'),'new_raw_representations':len(set(new_hash)-set(old_hash)),'absent_raw_representations':len(set(old_hash)-set(new_hash)),'changed_singleton_candidate_identities':changed,'ambiguous_candidate_identities':ambiguous,'note':'Absence is only snapshot absence; candidate identities are not asserted port-visit identities.'})
    output=Path(a.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps({'comparisons':results,'note':'Only checksum-valid 200-array snapshots are compared. Source timestamps are retained inside raw records without timezone interpretation.'},indent=2)+'\n');print(json.dumps({'comparisons':len(results)}))

if __name__=='__main__':main()
