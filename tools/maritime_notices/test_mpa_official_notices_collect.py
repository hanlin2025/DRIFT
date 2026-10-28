#!/usr/bin/env python3
import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('collector', Path(__file__).with_name('mpa_official_notices_collect.py'))
collector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collector)

class CollectorTests(unittest.TestCase):
    def test_listing_url_has_only_expected_query_values(self):
        url = collector.listing_url('category-id', '2026', 25)
        self.assertIn('type=category-id', url)
        self.assertIn('year=2026', url)
        self.assertIn('limit=25', url)

    def test_write_replaces_only_its_target(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observation.json'
            collector.write(path, b'{"synthetic":true}')
            self.assertEqual(path.read_bytes(), b'{"synthetic":true}')
            self.assertFalse(path.with_name(path.name + '.tmp').exists())

if __name__ == '__main__':
    unittest.main()
