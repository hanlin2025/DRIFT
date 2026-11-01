#!/usr/bin/env python3
"""Append-only NEA weather collector; historical retrievals are backfills."""
import argparse, csv, hashlib, json, os, sys, time, urllib.error, urllib.parse, urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

ENDPOINTS = {
 'two_hr_forecast':'https://api-open.data.gov.sg/v2/real-time/api/two-hr-forecast',
 'wind_speed':'https://api-open.data.gov.sg/v2/real-time/api/wind-speed',
 'wind_direction':'https://api-open.data.gov.sg/v2/real-time/api/wind-direction',
 'rainfall':'https://api-open.data.gov.sg/v2/real-time/api/rainfall'}
SGT=ZoneInfo('Asia/Singapore')

def load_env(path):
 p=Path(path)
 if p.exists():
  for line in p.read_text().splitlines():
   if '=' in line and not line.lstrip().startswith('#'):
    k,v=line.split('=',1);os.environ.setdefault(k.strip(),v.strip().strip('"'))
def url_for(endpoint,day=None,token=None):
 q={}
 if day:q['date']=day
 if token:q['paginationToken']=token
 return endpoint+('?' + urllib.parse.urlencode(q) if q else '')
def request(url,key):
 h={'Accept':'application/json','User-Agent':'DRIFT-maritime-data-collector/0.2 (public-data research)'}
 if key:h['x-api-key']=key
 with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=30) as r:return r.status,dict(r.headers.items()),r.read()
def unique(out,stem,suffix):
 p=out/f'{stem}{suffix}';n=1
 while p.exists():p=out/f'{stem}_{n}{suffix}';n+=1
 return p
def fetch(url,key,retries,pause):
 for attempt in range(retries+1):
  try:return *request(url,key),attempt,''
  except Exception as e:
   status=headers=raw=None
   if isinstance(e,urllib.error.HTTPError):status=e.code;headers=dict(e.headers.items()) if e.headers else {};raw=e.read()
   retry=status==429 or status is None or 500<=status<600
   if not retry or attempt==retries:return status,headers or {},raw or b'',attempt,f'{type(e).__name__}:{status or "network"}'
   try: wait=float((headers or {}).get('Retry-After',pause*(2**attempt)))
   except ValueError:wait=pause*(2**attempt)
   # Public unauthenticated requests need a conservative back-off even when
   # the server omits Retry-After.
   if status==429:wait=max(wait,5.0*(2**attempt))
   time.sleep(max(0,wait))
def metadata(data):
 d=data.get('data',{});items=d.get('items',[]);readings=d.get('readings',[]);first=items[0] if items else (readings[0] if readings else {})
 return {'source_first_timestamp':first.get('timestamp') or first.get('update_timestamp'),'forecast_issuance_timestamp':first.get('update_timestamp') if items else None,'forecast_valid_period':first.get('valid_period') if items else None,'reading_type':d.get('readingType'),'reading_unit':d.get('readingUnit'),'station_count':len(d.get('stations',[])),'area_count':len(d.get('area_metadata',[]))}
def count(dataset,data):
 d=data.get('data',{})
 return sum(len(x.get('forecasts',[])) for x in d.get('items',[])) if dataset=='two_hr_forecast' else sum(len(x.get('data',[])) for x in d.get('readings',[]))
def save_page(out,dataset,day,page,url,started,completed,status,headers,raw,data,token,next_token,retries,error):
 stamp=started.strftime('%Y%m%dT%H%M%S%fZ');stem=f'{dataset}_{day or "live"}_{stamp}_page{page:04d}'
 raw_path=unique(out,stem,'.json') if raw else None
 if raw_path:raw_path.write_bytes(raw)
 m={'dataset':dataset,'requested_singapore_date':day or '','collection_label':'HISTORICAL_BACKFILL' if day else 'LIVE_SNAPSHOT','source_endpoint':url,'retrieval_started_utc':started.isoformat(),'retrieval_completed_utc':completed.isoformat(),'http_status':status,'raw_file':raw_path.name if raw_path else '','sha256':hashlib.sha256(raw).hexdigest() if raw else '','page_number':page,'request_pagination_token_present':bool(token),'next_pagination_token_present':bool(next_token),'retry_count':retries,'error':error,'response_content_type':headers.get('Content-Type',''),'record_count':count(dataset,data) if data else None,**(metadata(data) if data else {})}
 mp=unique(out,stem,'.manifest.json');mp.write_text(json.dumps(m,indent=2,sort_keys=True)+'\n');return m,raw_path
def state_path(out,dataset,day):return out/f'.{dataset}_{day or "live"}.pagination-state.json'
def write_state(out,dataset,day,page,next_token,complete):
 # Derived cursor state is replaceable; raw API evidence is never overwritten.
 state_path(out,dataset,day).write_text(json.dumps({'dataset':dataset,'requested_singapore_date':day or '','last_page_number':page,'next_pagination_token':next_token,'pagination_complete':complete,'updated_utc':datetime.now(timezone.utc).isoformat()},indent=2)+'\n')
def resume_cursor(out,dataset,day):
 """Return the next token from the newest successful saved page, if any."""
 sp=state_path(out,dataset,day)
 if sp.exists():
  try:
   s=json.loads(sp.read_text())
   if s.get('pagination_complete'):return 'COMPLETE',(s.get('last_page_number') or 0)+1
   if s.get('next_pagination_token'):return s['next_pagination_token'],(s.get('last_page_number') or 0)+1
  except (OSError,json.JSONDecodeError):pass
 candidates=[]
 for p in out.glob(f'{dataset}_{day}_*.manifest.json'):
  try:
   m=json.loads(p.read_text())
   if not m.get('error') and m.get('raw_file') and m.get('next_pagination_token_present'):candidates.append((p.stat().st_mtime,m))
  except (OSError,json.JSONDecodeError):pass
 if not candidates:return None,1
 _,m=max(candidates,key=lambda x:x[0])
 try:
  token=json.loads((out/m['raw_file']).read_text()).get('data',{}).get('paginationToken')
  return token,(m.get('page_number') or 0)+1
 except (OSError,json.JSONDecodeError):return None,1
def collect(dataset,endpoint,out,key,day,pause=.5,retries=3,resume=False):
 token,page=resume_cursor(out,dataset,day) if resume else (None,1)
 if token=='COMPLETE':return {'dataset':dataset,'requested_singapore_date':day or '','pages':[],'raw_pages':[],'pagination_complete':True,'failure':'','already_complete':True}
 seen=set();pages=[];raws=[];complete=True;failure=''
 while True:
  url=url_for(endpoint,day,token);started=datetime.now(timezone.utc);status,headers,raw,n,error=fetch(url,key,retries,pause);completed=datetime.now(timezone.utc);data=None
  if not error:
   try:
    data=json.loads(raw)
    if not isinstance(data,dict) or data.get('code') not in (0,'0',None):error=f'APIError:{data.get("code") if isinstance(data,dict) else "invalid"}'
   except (UnicodeDecodeError,json.JSONDecodeError):error='MalformedJSON'
  next_token=data.get('data',{}).get('paginationToken') if data and not error else None
  m,r=save_page(out,dataset,day,page,url,started,completed,status,headers,raw,data,token,next_token,n,error);pages.append(m)
  if r:raws.append(r)
  # Retain the cursor used for the failed request: retrying it cannot skip the
  # missing page, whereas advancing would.
  if error:complete=False;failure=error;write_state(out,dataset,day,page-1,token,False);break
  if not next_token:write_state(out,dataset,day,page,None,True);break
  if next_token==token or next_token in seen:complete=False;failure='RepeatedPaginationToken';break
  seen.add(next_token);write_state(out,dataset,day,page,next_token,False);token=next_token;page+=1;time.sleep(max(0,pause))
 return {'dataset':dataset,'requested_singapore_date':day or '','pages':pages,'raw_pages':raws,'pagination_complete':complete,'failure':failure}
def stations(payload):
 out={}
 for x in payload.get('stations',[]):
  loc=x.get('location') or x.get('labelLocation') or x.get('label_location') or {};out[x.get('id')]={'station_name':x.get('name'),'latitude':loc.get('latitude'),'longitude':loc.get('longitude')}
 return out
FIELDS=['dataset','requested_singapore_date','source_raw_file','page_number','source_timestamp','forecast_issuance_timestamp','valid_start','valid_end','area','forecast','station_id','station_name','latitude','longitude','value','unit']
def normalise(result,normdir):
 stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ');path=unique(normdir,f"{result['dataset']}_{result['requested_singapore_date'] or 'live'}_{stamp}",'.csv');rows=[]
 for raw,m in zip(result['raw_pages'],result['pages']):
  if m['error']:continue
  data=json.loads(raw.read_text());d=data.get('data',{})
  if result['dataset']=='two_hr_forecast':
   areas={x.get('name'):x.get('label_location') or x.get('labelLocation') or {} for x in d.get('area_metadata',[])}
   for item in d.get('items',[]):
    for f in item.get('forecasts',[]):
     loc=areas.get(f.get('area'),{});rows.append({'dataset':result['dataset'],'requested_singapore_date':result['requested_singapore_date'],'source_raw_file':raw.name,'page_number':m['page_number'],'source_timestamp':item.get('timestamp'),'forecast_issuance_timestamp':item.get('update_timestamp'),'valid_start':(item.get('valid_period') or {}).get('start'),'valid_end':(item.get('valid_period') or {}).get('end'),'area':f.get('area'),'forecast':f.get('forecast'),'station_id':'','station_name':'','latitude':loc.get('latitude'),'longitude':loc.get('longitude'),'value':'','unit':''})
  else:
   sm=stations(d)
   for reading in d.get('readings',[]):
    for value in reading.get('data',[]):
     s=sm.get(value.get('stationId'),{});rows.append({'dataset':result['dataset'],'requested_singapore_date':result['requested_singapore_date'],'source_raw_file':raw.name,'page_number':m['page_number'],'source_timestamp':reading.get('timestamp'),'forecast_issuance_timestamp':'','valid_start':'','valid_end':'','area':'','forecast':'','station_id':value.get('stationId'),'station_name':s.get('station_name'),'latitude':s.get('latitude'),'longitude':s.get('longitude'),'value':value.get('value'),'unit':d.get('readingUnit')})
 # API retries/pages can overlap; preserve the first raw provenance row but
 # never duplicate a logical weather observation in this derived output.
 seen=set();unique_rows=[]
 for row in rows:
  key=(row['dataset'],row['source_timestamp'],row['area'] or row['station_id'])
  if key not in seen:seen.add(key);unique_rows.append(row)
 rows=unique_rows
 with path.open('w',newline='') as f:w=csv.DictWriter(f,fieldnames=FIELDS);w.writeheader();w.writerows(rows)
 return path,rows
def report(result,rows,normpath):
 ts=sorted({str(x['source_timestamp']) for x in rows if x.get('source_timestamp')});locs={x.get('area') or x.get('station_id') for x in rows if x.get('area') or x.get('station_id')};key=('area' if result['dataset']=='two_hr_forecast' else 'station_id');pairs=[(x.get('source_timestamp'),x.get(key)) for x in rows]
 return {'dataset':result['dataset'],'requested_singapore_date':result['requested_singapore_date'],'collection_label':'HISTORICAL_BACKFILL' if result['requested_singapore_date'] else 'LIVE_SNAPSHOT','api_pages_retrieved':len(result['pages']),'pagination_complete':result['pagination_complete'],'failure':result['failure'],'total_observation_or_forecast_records':len(rows),'unique_source_timestamps':len(ts),'first_source_timestamp':ts[0] if ts else None,'last_source_timestamp':ts[-1] if ts else None,'unique_stations_or_areas':len(locs),'duplicate_timestamp_location_records':len(pairs)-len(set(pairs)),'missing_data_indicator':not bool(rows) or not result['pagination_complete'],'normalized_file':str(normpath),'timezone_note':'Requested dates are Singapore calendar dates. Source timestamp offsets are preserved verbatim; no conversion is assumed.','prospective_note':'Historical backfill retrieval time is not the source observation/forecast issuance time and is not prospective DRIFT evidence.'}
def dates(a):
 if a.end and not a.start:raise ValueError('--end requires --start')
 if a.start:first=date.fromisoformat(a.start);last=date.fromisoformat(a.end or a.start)
 elif a.date:first=last=date.fromisoformat(a.date)
 else:return [None]
 if last<first:raise ValueError('--end must not precede --start')
 if last>datetime.now(SGT).date():raise ValueError('Future dates are not collected; current-day coverage may be incomplete.')
 return [(first+timedelta(days=n)).isoformat() for n in range((last-first).days+1)]
def write_coverage_matrix(normdir,reports):
 """One offline range summary; each entry retains its own completion state."""
 stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
 path=unique(normdir,f'nea_weather_coverage_matrix_{stamp}','.json')
 path.write_text(json.dumps({'generated_utc':datetime.now(timezone.utc).isoformat(),'entries':reports,'complete_entries':sum(bool(x['pagination_complete']) for x in reports),'incomplete_entries':sum(not x['pagination_complete'] for x in reports)},indent=2,sort_keys=True)+'\n')
 return path
def main():
 p=argparse.ArgumentParser();p.add_argument('--env',default='.env');p.add_argument('--output',default='data/external/maritime/raw/nea_weather');p.add_argument('--date');p.add_argument('--start');p.add_argument('--end');p.add_argument('--pause-seconds',type=float,default=2.0);p.add_argument('--max-retries',type=int,default=4);p.add_argument('--resume',action='store_true',help='Continue from the newest successful saved page for each requested dataset/date.');p.add_argument('--datasets',nargs='*',choices=ENDPOINTS,default=list(ENDPOINTS));a=p.parse_args()
 if a.date and a.start:p.error('use either --date or --start/--end')
 try:ds=dates(a)
 except ValueError as e:p.error(str(e))
 load_env(a.env);out=Path(a.output);out.mkdir(parents=True,exist_ok=True);norm=out/'normalized';norm.mkdir(parents=True,exist_ok=True);key=os.getenv('DATA_GOV_SG_API_KEY','');reports=[]
 for day in ds:
  for dataset in a.datasets:
   result=collect(dataset,ENDPOINTS[dataset],out,key,day,a.pause_seconds,a.max_retries,a.resume);path,rows=normalise(result,norm);item=report(result,rows,path);reports.append(item);print(json.dumps(item,sort_keys=True));time.sleep(max(0,a.pause_seconds))
 rp=unique(norm,f"nea_weather_coverage_{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')}",'.json');rp.write_text(json.dumps(reports,indent=2,sort_keys=True)+'\n');matrix=write_coverage_matrix(norm,reports);print(json.dumps({'coverage_report':str(rp),'coverage_matrix':str(matrix),'entries':len(reports)}));return 0 if all(x['pagination_complete'] for x in reports) else 2
if __name__=='__main__':sys.exit(main())
