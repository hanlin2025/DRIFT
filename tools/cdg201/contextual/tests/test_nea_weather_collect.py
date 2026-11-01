import json,tempfile,unittest
from pathlib import Path
import nea_weather_collect as nea
def raw(token=None):
 d={'stations':[{'id':'S1','name':'One','location':{'latitude':1,'longitude':2}}],'readings':[{'timestamp':'2026-10-23T00:00:00+08:00','data':[{'stationId':'S1','value':1}]}]}
 if token:d['paginationToken']=token
 return json.dumps({'code':0,'data':d}).encode()
class TestCollector(unittest.TestCase):
 def setUp(self):self.old=nea.request
 def tearDown(self):nea.request=self.old
 def test_historical_date(self):self.assertIn('date=2026-10-23',nea.url_for('https://x','2026-10-23'))
 def test_pages_and_append_only(self):
  replies=[raw('next'),raw()];nea.request=lambda *x:(200,{},replies.pop(0))
  with tempfile.TemporaryDirectory() as d:
   r=nea.collect('rainfall','https://x',Path(d),'','2026-10-23',0);self.assertTrue(r['pagination_complete']);self.assertEqual(len(r['pages']),2);self.assertEqual(len([p for p in Path(d).glob('*_page*.json') if not p.name.endswith('.manifest.json')]),2)
 def test_repeated_token(self):
  nea.request=lambda *x:(200,{},raw('again'))
  with tempfile.TemporaryDirectory() as d:
   r=nea.collect('rainfall','https://x',Path(d),'','2026-10-23',0);self.assertFalse(r['pagination_complete']);self.assertEqual(r['failure'],'RepeatedPaginationToken')
 def test_empty(self):
  nea.request=lambda *x:(200,{},json.dumps({'code':0,'data':{'stations':[],'readings':[]}}).encode())
  with tempfile.TemporaryDirectory() as d:
   r=nea.collect('rainfall','https://x',Path(d),'','2026-10-23',0);p,rows=nea.normalise(r,Path(d));self.assertTrue(nea.report(r,rows,p)['missing_data_indicator'])
 def test_repeated_backfill_is_append_only(self):
  nea.request=lambda *x:(200,{},raw())
  with tempfile.TemporaryDirectory() as d:
   nea.collect('rainfall','https://x',Path(d),'','2026-10-23',0);nea.collect('rainfall','https://x',Path(d),'','2026-10-23',0)
   raws=[p for p in Path(d).glob('*_page*.json') if not p.name.endswith('.manifest.json')];self.assertEqual(len(raws),2);self.assertNotEqual(raws[0].name,raws[1].name)
 def test_malformed_json_saved(self):
  nea.request=lambda *x:(200,{},b'bad')
  with tempfile.TemporaryDirectory() as d:
   r=nea.collect('rainfall','https://x',Path(d),'','2026-10-23',0);self.assertFalse(r['pagination_complete']);self.assertTrue(r['raw_pages'][0].exists())
 def test_429_retries(self):
  calls={'n':0}
  def f(*x):
   calls['n']+=1
   if calls['n']==1:raise __import__('urllib.error').error.HTTPError('x',429,'slow',{},None)
   return 200,{},raw()
  nea.request=f
  with tempfile.TemporaryDirectory() as d:r=nea.collect('rainfall','https://x',Path(d),'','2026-10-23',0,1);self.assertTrue(r['pagination_complete']);self.assertEqual(r['pages'][0]['retry_count'],1)
 def test_interrupted_collection_resumes_the_missing_page(self):
  calls=[];replies=[raw('next'),b'bad']
  def first(url,*x):calls.append(url);return 200,{},replies.pop(0)
  nea.request=first
  with tempfile.TemporaryDirectory() as d:
   out=Path(d);failed=nea.collect('rainfall','https://x',out,'','2026-10-23',0)
   self.assertFalse(failed['pagination_complete'])
   nea.request=lambda url,*x:(calls.append(url) or (200,{},raw()))
   recovered=nea.collect('rainfall','https://x',out,'','2026-10-23',0,resume=True)
   self.assertTrue(recovered['pagination_complete']);self.assertIn('paginationToken=next',calls[-1])
   again=nea.collect('rainfall','https://x',out,'','2026-10-23',0,resume=True)
   self.assertTrue(again['already_complete']);self.assertEqual(again['pages'],[])
 def test_normalized_retry_rows_are_deduplicated(self):
  replies=[raw('next'),raw()];nea.request=lambda *x:(200,{},replies.pop(0))
  with tempfile.TemporaryDirectory() as d:
   r=nea.collect('rainfall','https://x',Path(d),'','2026-10-23',0);_,rows=nea.normalise(r,Path(d));self.assertEqual(len(rows),1)
if __name__=='__main__':unittest.main()
