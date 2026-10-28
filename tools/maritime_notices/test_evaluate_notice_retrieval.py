#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('evaluation', Path(__file__).with_name('evaluate_notice_retrieval.py'))
evaluation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluation)

class EvaluationTests(unittest.TestCase):
    def test_reports_independent_labels_and_denominators(self):
        with tempfile.TemporaryDirectory() as directory:
            index = Path(directory)
            (index / 'n.json').write_text(json.dumps({'document_id': 'synthetic', 'source_url': 'https://example.invalid', 'pages': [{'page': 1, 'text': 'DREDGING WORKS AT GUL BASIN'}], 'facts': {'scope': 'Mariners near Gul Basin', 'effective_period': 'With effect from 14 Apr 2099 to 13 Oct 2099.', 'fact_pages': {'scope': [1]}}}))
            cases = [
                {'id': 'positive', 'query': 'Gul Basin dredging on 2099-06-01', 'expected': 'candidate', 'expected_document_ids': ['synthetic'], 'compatibility_assertions': [{'document_id': 'synthetic', 'criterion': 'effective_period', 'value': 'COMPATIBLE'}], 'citation_assertions': [{'document_id': 'synthetic', 'page': 1}]},
                {'id': 'negative', 'query': 'bulk carrier anchoring at Gul Basin', 'expected': 'none'},
                {'id': 'abstain', 'query': 'Did this cause vessel TEST ETA delay?', 'expected': 'abstain'},
            ]
            metrics = evaluation.evaluate(cases, index)['metrics']
            self.assertEqual(metrics['positive_top1_accuracy'], {'correct': 1, 'total': 1, 'rate': 1.0})
            self.assertEqual(metrics['negative_control_rejection']['correct'], 1)
            self.assertEqual(metrics['unsupported_conclusion_abstention']['correct'], 1)
            self.assertEqual(metrics['citation_correctness']['correct'], 1)
