#!/usr/bin/env python3
import importlib.util, json, tempfile, unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def load(name):
 spec=importlib.util.spec_from_file_location(name,ROOT/name);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
collect=load('mpa_declaration_collect.py'); compare=load('compare_mpa_declaration_snapshots.py')

class DeclarationCollectionTests(unittest.TestCase):
 def test_array_schema_including_empty_is_valid(self):
  self.assertEqual(collect.parse_array(b'[]'),[])
  self.assertEqual(collect.parse_array(b'[{"x":1}]'),[{"x":1}])
  self.assertIsNone(collect.parse_array(b'{"data":[]}'))
  self.assertIsNone(collect.parse_array(b'broken'))
 def test_atomic_write_replaces_complete_file(self):
  with tempfile.TemporaryDirectory() as tmp:
   path=Path(tmp)/'evidence.json';collect.atomic_write(path,b'one');collect.atomic_write(path,b'two');self.assertEqual(path.read_bytes(),b'two');self.assertFalse((Path(tmp)/'evidence.json.tmp').exists())
 def test_candidate_identity_requires_imo_and_uses_context(self):
  record={'vesselParticulars':{'imoNumber':'9351048','callSign':'9V7259','vesselName':'KOTA LARIS'},'location':'P29'}
  self.assertEqual(compare.candidate_key('arrival_declaration',record),'arrival_declaration|9351048|9V7259|KOTA LARIS|P29')
  self.assertEqual(compare.candidate_key('arrival_declaration',{'vesselParticulars':{'vesselName':'x'}}),'')

if __name__=='__main__':unittest.main()
