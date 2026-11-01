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

    def test_version_observation_is_source_backed_or_unknown(self):
        amended = collector.version_observation({'id': 'synthetic-1', 'title': 'Amendment to Notice 8', 'release_date': '2099-01-01', 'status': ''})
        unknown = collector.version_observation({'id': 'synthetic-2', 'title': 'Routine advisory'})
        self.assertEqual(amended['relationship_status'], 'SOURCE_BACKED')
        self.assertEqual(unknown['observed_status'], 'UNKNOWN')
        self.assertEqual(unknown['publication_time_status'], 'UNKNOWN')

    def test_backfill_prospective_and_same_second_collision_are_distinct(self):
        now = collector.datetime(2099, 1, 2, tzinfo=collector.timezone.utc)
        self.assertEqual(collector.observation_kind('historical-backfill', '2099', now), 'HISTORICAL_BACKFILL')
        self.assertEqual(collector.observation_kind('prospective', '2099', now), 'PROSPECTIVE_OBSERVATION')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'notice_2099_1.json').write_text('{}')
            self.assertEqual(collector.unique_stem(root, 'notice_2099_1'), 'notice_2099_1-2')

    def test_failed_response_is_not_valid_coverage(self):
        self.assertIsNone(collector.listing_items(b'not-json'))
        self.assertIsNone(collector.listing_items(b'{"data": {}}'))

    def test_full_page_is_not_complete_without_source_count_or_later_pages(self):
        page = [{}] * 100
        self.assertFalse(collector.coverage_complete(page, None, []))
        self.assertFalse(collector.coverage_complete(page, 101, []))
        self.assertTrue(collector.coverage_complete(page, 100, []))

if __name__ == '__main__':
    unittest.main()
