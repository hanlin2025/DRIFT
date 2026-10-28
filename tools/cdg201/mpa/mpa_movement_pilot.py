#!/usr/bin/env python3
"""Append-only, small MPA movement-by-call-sign pilot collector."""
import argparse,csv,hashlib,json,os,urllib.error,urllib.parse,urllib.request
from datetime import datetime,timezone
from pathlib import Path
WATCH=[('9351048','KOTA LARIS','9V7259','name-based container-service candidate'),('9060649','MSC MONICA III','D5EA3','name-based container-service candidate'),('9977713','CMA CGM BAALBECK','FNYJ','name-based container-service candidate'),('9943267','EVER ACME','9V7305','name-based container-service candidate'),('9790270','MARAN AJAX','SVCQ5','comparison candidate; type unverified')]
def env(p):
 for l in Path(p).read_text().splitlines():
  if '=' in l and not l.lstrip().startswith('#'):
   k,v=l.split('=',1);os.environ.setdefault(k.strip(),v.strip().strip('"'))
def get(url,key):
 with urllib.request.urlopen(urllib.request.Request(url,headers={'apikey':key,'Accept':'application/json'}),timeout=30) as r:return r.status,r.read()
def main():
 a=argparse.ArgumentParser();a.add_argument('--env',required=True);a.add_argument('--raw-output',required=True);a.add_argument('--analysis-output',required=True);a.add_argument('--callsigns',nargs='*');x=a.parse_args();env(x.env);base=os.environ['MPA_OCEANSX_API_BASE'].rstrip('/');key=os.environ['MPA_OCEANSX_API_KEY'];raw=Path(x.raw_output);ana=Path(x.analysis_output);raw.mkdir(parents=True,exist_ok=True);ana.mkdir(parents=True,exist_ok=True)
 watch=[w for w in WATCH if not x.callsigns or w[2] in x.callsigns];stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ');rows=[];quality=[]
 for imo,name,call,note in watch:
  url=base+'/api/v1/vessel/movements/1.0.0/callsign/'+urllib.parse.quote(call,safe='');start=datetime.now(timezone.utc);status=None;body=b'';err=''
  try:status,body=get(url,key)
  except urllib.error.HTTPError as e:status=e.code;body=e.read();err=f'HTTPError:{e.code}'
  except Exception as e:err=type(e).__name__
  end=datetime.now(timezone.utc);stem=f'movements_callsign_{call}_{start.strftime("%Y%m%dT%H%M%SZ")}';rp=raw/(stem+'.json')
  if body:rp.write_bytes(body)
  try:data=json.loads(body) if body else []
  except json.JSONDecodeError:data=[];err=err or 'MalformedJSON'
  records=data if isinstance(data,list) else (data.get('data',[]) if isinstance(data,dict) else [])
  m={'dataset':'vessel_movements_by_callsign','query_callsign':call,'selected_imo':imo,'selection_note':note,'endpoint_without_credentials':url,'retrieval_started_utc':start.isoformat(),'retrieval_completed_utc':end.isoformat(),'http_status':status,'raw_file':rp.name if body else '','sha256':hashlib.sha256(body).hexdigest() if body else '','record_count':len(records),'error':err}
  (raw/(stem+'.manifest.json')).write_text(json.dumps(m,indent=2)+'\n');quality.append(m)
  for r in records:
   p=r.get('vesselParticulars',{}) if isinstance(r,dict) else {};rows.append({'source_raw_file':rp.name,'retrieval_utc':start.isoformat(),'query_callsign':call,'selected_imo':imo,'returned_imo':p.get('imoNumber'),'returned_name':p.get('vesselName'),'returned_callsign':p.get('callSign'),'movement_start':r.get('movementStartDateTime'),'movement_end':r.get('movementEndDateTime'),'movement_status':r.get('movementStatus'),'movement_type':r.get('movementType'),'location_from':r.get('locationFrom'),'location_to':r.get('locationTo'),'movement_draft':r.get('movementDraft'),'movement_height':r.get('movementHeight')})
 fields=['source_raw_file','retrieval_utc','query_callsign','selected_imo','returned_imo','returned_name','returned_callsign','movement_start','movement_end','movement_status','movement_type','location_from','location_to','movement_draft','movement_height']
 with (ana/f'movement_pilot_normalized_{stamp}.csv').open('w',newline='') as f:w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
 (ana/f'movement_pilot_quality_{stamp}.json').write_text(json.dumps({'watchlist':watch,'requests':quality,'normalized_rows':len(rows),'note':'Codes are preserved exactly; no berth/type interpretation.'},indent=2)+'\n');print(json.dumps({'requests':len(watch),'responses_with_rows':sum(q['record_count']>0 for q in quality),'normalized_rows':len(rows),'errors':sum(bool(q['error']) for q in quality)}))
if __name__=='__main__':main()
