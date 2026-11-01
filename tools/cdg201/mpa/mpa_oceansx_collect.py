#!/usr/bin/env python3
"""Manual, append-only OCEANS-X snapshot collector. Requires a subscribed API key."""
import argparse,hashlib,json,os,sys,urllib.request,urllib.error
from datetime import date,timedelta,datetime,timezone
from zoneinfo import ZoneInfo
from pathlib import Path
def env(path):
 if not Path(path).exists(): return
 for line in Path(path).read_text().splitlines():
  if '=' in line and not line.lstrip().startswith('#'):
   k,v=line.split('=',1); os.environ.setdefault(k.strip(),v.strip().strip('"'))
def get(url,key):
 req=urllib.request.Request(url,headers={'apikey':key,'Accept':'application/json'})
 with urllib.request.urlopen(req,timeout=30) as r:return r.read(),r.status
def main():
 p=argparse.ArgumentParser();p.add_argument('--env',default='.env');p.add_argument('--start');p.add_argument('--days',type=int,default=4);p.add_argument('--rolling',action='store_true',help='Use Singapore today-3..today for reported and today-3..today+3 for due feeds.');p.add_argument('--feed-group',choices=('all','expected','reported'),default='all');p.add_argument('--lookback-days',type=int,default=3);p.add_argument('--lookahead-days',type=int,default=3);p.add_argument('--output',default='data/external/maritime/raw/mpa_oceansx');a=p.parse_args();env(a.env)
 key=os.getenv('MPA_OCEANSX_API_KEY');base=os.getenv('MPA_OCEANSX_API_BASE','').rstrip('/')
 if not key or not base:sys.exit('Set MPA_OCEANSX_API_KEY and MPA_OCEANSX_API_BASE in .env; do not commit .env.')
 templates={'due_to_arrive':os.getenv('MPA_DUE_TO_ARRIVE_PATH',''),'arrivals':os.getenv('MPA_ARRIVALS_PATH',''),'due_to_depart':os.getenv('MPA_DUE_TO_DEPART_PATH',''),'departures':os.getenv('MPA_DEPARTURES_PATH',''),'weather':os.getenv('MPA_WEATHER_PATH','')}
 root=Path(a.output);root.mkdir(parents=True,exist_ok=True); run_id='mpa-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'); start=date.fromisoformat(a.start) if a.start else datetime.now(ZoneInfo('Asia/Singapore')).date()
 # Historical event feeds and pending/due feeds have deliberately different
 # query horizons. Generic --start/--days retains the earlier manual mode.
 event_days=[start+timedelta(days=n) for n in range(a.days)]
 due_days=event_days
 if a.rolling:
  event_days=[start+timedelta(days=n) for n in range(-a.lookback_days,1)]
  due_days=[start+timedelta(days=n) for n in range(-a.lookback_days,a.lookahead_days+1)]
 for dataset,template in templates.items():
  if a.feed_group=='expected' and not dataset.startswith('due_to_'):continue
  if a.feed_group=='reported' and dataset.startswith('due_to_'):continue
  if not template:continue
  days=due_days if dataset.startswith('due_to_') else event_days
  for request_day in days:
   day=request_day.isoformat()
   begin=datetime.now(timezone.utc)
   endpoint=base+template.format(date=day)
   raw=b''; parsed=[]; err=''; http_status=None; schema_valid=False
   try:
    raw,http_status=get(endpoint,key)
    if http_status==204 and not raw:
     err='HTTPNoContent'
    else:
     parsed=json.loads(raw)
     if not isinstance(parsed,list):
      err='SchemaNotArray'
     else:
      schema_valid=True
   except urllib.error.HTTPError as e:
    raw=e.read(); http_status=e.code; err=f'HTTPError:{e.code}'
   except json.JSONDecodeError:
    err='MalformedJSON'
   except Exception as e:
    err=type(e).__name__
   end=datetime.now(timezone.utc); stamp=begin.strftime('%Y%m%dT%H%M%SZ'); out=root/f'{dataset}_{day}_{stamp}.json'
   if raw: out.write_bytes(raw)
   meta={'collection_run_id':run_id,'dataset':dataset,'queried_date':day,'retrieval_started_utc':begin.isoformat(),'retrieval_completed_utc':end.isoformat(),'endpoint_without_credentials':endpoint,'http_status':http_status,'response_body_present':bool(raw),'schema_valid':schema_valid,'raw_file':out.name if raw else '','record_count':len(parsed) if schema_valid else None,'sha256':hashlib.sha256(raw).hexdigest() if raw else '','error':err}
   (root/f'{dataset}_{day}_{stamp}.manifest.json').write_text(json.dumps(meta,indent=2))
if __name__=='__main__':main()
