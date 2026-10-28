#!/usr/bin/env python3
"""Report manifest/checksum health for a configurable MPA snapshot root."""
import argparse
import json,hashlib
from pathlib import Path


def main():
 p=argparse.ArgumentParser()
 p.add_argument("--root", type=Path, required=True, help="MPA raw snapshot/manifest directory")
 p.add_argument("--output", type=Path, required=True, help="Derived health JSON path")
 a=p.parse_args();r=a.root;out=a.output;rows=[]
 if not r.is_dir(): raise SystemExit(f"Snapshot root does not exist: {r}")
 for p in r.glob('*.manifest.json'):
  try:m=json.loads(p.read_text())
  except:continue
  raw=m.get('raw_file','');rp=r/raw;ok=bool(raw and rp.exists() and m.get('sha256') and hashlib.sha256(rp.read_bytes()).hexdigest()==m['sha256'])
  rows.append({'dataset':m.get('dataset'),'queried_date':m.get('queried_date'),'retrieval_utc':m.get('retrieval_started_utc'),'success':not bool(m.get('error')),'checksum_ok':ok,'error':m.get('error')})
 out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps({'requests':len(rows),'successful':sum(x['success'] for x in rows),'checksum_valid':sum(x['checksum_ok'] for x in rows)}))


if __name__ == '__main__': main()
