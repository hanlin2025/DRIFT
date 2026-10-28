#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('rag', Path(__file__).with_name('offline_notice_rag.py'))
rag = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rag)

class OfflineNoticeRagTests(unittest.TestCase):
    def test_extracts_facts_with_page_citations(self):
        result = rag.facts(['PORT MARINE NOTICE NO. 9 OF 2099\nApplicable to: Mariners near Fictional Basin\nWith effect from 14 Apr 2099 to 13 Oct 2099\nThis notice supersedes Port Marine Notice No. 8 of 2099.\nKeep well clear.'])
        self.assertEqual(result['notice_identifier'], '9 OF 2099')
        self.assertEqual(result['fact_pages']['notice_identifier'], [1])
        self.assertIn('8 of 2099', result['supersedes'])

    def test_discovery_is_deterministic_and_can_abstain(self):
        with tempfile.TemporaryDirectory() as directory:
            index = Path(directory)
            (index / 'n.json').write_text(json.dumps({'document_id': 'synthetic', 'source_url': 'https://example.invalid/notice', 'pages': [{'page': 1, 'text': 'synthetic dredging at Fictional Basin'}], 'facts': {}}))
            self.assertEqual(rag.query(index, 'dredging Fictional')['results'][0]['document_id'], 'synthetic')
            self.assertTrue(rag.query(index, 'unrelated glacier')['abstain'])

    def test_identical_local_pdf_content_is_returned_once(self):
        with tempfile.TemporaryDirectory() as directory:
            root, index = Path(directory), Path(directory) / 'index'
            (root / 'pdfs').mkdir(parents=True)
            (root / 'pdfs' / 'same.pdf').write_bytes(b'%PDF-synthetic')
            index.mkdir()
            record = {'source_url': 'https://example.invalid/notice', 'local_pdf': 'same.pdf', 'pages': [{'page': 1, 'text': 'synthetic dredging'}], 'facts': {}}
            for name in ('first', 'second'):
                (index / f'{name}.json').write_text(json.dumps({**record, 'document_id': name}))
            self.assertEqual(len(rag.query(index, 'dredging', private_root=root)['results']), 1)

if __name__ == '__main__':
    unittest.main()
