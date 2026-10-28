#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('rag', Path(__file__).with_name('offline_notice_rag.py'))
rag = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rag)

def synthetic_pdf(page_one, page_two):
    """A generated two-page PDF fixture; extraction still uses real pdftotext."""
    def stream(lines):
        text = '\n'.join(f'({line}) Tj T*' for line in lines).encode()
        return b'<< /Length ' + str(len(text)).encode() + b' >>\nstream\nBT /F1 12 Tf 14 TL 72 720 Td\n' + text + b'\nET\nendstream'
    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', stream(page_one), stream(page_two),
    ]
    data, offsets = b'%PDF-1.4\n', [0]
    for number, body in enumerate(objects, 1):
        offsets.append(len(data)); data += f'{number} 0 obj\n'.encode() + body + b'\nendobj\n'
    xref = len(data)
    data += f'xref\n0 {len(objects) + 1}\n0000000000 65535 f \n'.encode()
    data += b''.join(f'{offset:010d} 00000 n \n'.encode() for offset in offsets[1:])
    return data + f'trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()

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

    def test_real_multpage_pdf_extraction_keeps_page_two_citation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pdf = root / 'synthetic.pdf'
            pdf.write_bytes(synthetic_pdf(
                ['PORT MARINE NOTICE NO. 9 OF 2099', 'Applicable to: Mariners near Fictional Basin', 'With effect from 14 Apr 2099 to 13 Oct 2099'],
                ['Keep well clear and maintain a listening watch.']))
            result = rag.extract(pdf, 'https://example.invalid/notice', 'synthetic', root / 'index')
            self.assertTrue(result['ok'])
            data = json.loads((root / 'index' / 'synthetic.json').read_text())
            self.assertEqual([page['page'] for page in data['pages']], [1, 2])
            self.assertEqual(data['facts']['fact_pages']['notice_identifier'], [1])
            self.assertEqual(data['facts']['fact_pages']['precautions'], [2])

    def test_missing_and_empty_pdfs_fail_without_index_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertFalse(rag.extract(root / 'missing.pdf', 'https://example.invalid', 'missing', root / 'index')['ok'])
            empty = root / 'empty.pdf'; empty.write_bytes(b'')
            self.assertFalse(rag.extract(empty, 'https://example.invalid', 'empty', root / 'index')['ok'])

if __name__ == '__main__':
    unittest.main()
