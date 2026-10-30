#!/usr/bin/env python3
"""Evaluate a caller-supplied synthetic case file; never ships real evidence."""
import argparse
import importlib.util
import json
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('rag', Path(__file__).with_name('offline_notice_rag.py'))
rag = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rag)

def ratio(correct, total):
    return {'correct': correct, 'total': total, 'rate': round(correct / total, 3) if total else None}

def evaluate(cases, index):
    rows, positive, negative, abstention, compatibility, citations = [], [], [], [], [], []
    for case in cases:
        raw = rag.discover(index, case['query'])
        answer = rag.query(index, case['query'])
        actual = [item['document_id'] for item in answer['results']]
        wanted = set(case.get('expected_document_ids', []))
        row = {**case, 'discovered_document_ids': [item['document_id'] for item in raw], 'actual_document_ids': actual, 'abstained': answer['abstain']}
        rows.append(row)
        if case['expected'] == 'candidate':
            positive.append((row, wanted))
        elif case['expected'] == 'none':
            negative.append(row)
        elif case['expected'] == 'abstain':
            abstention.append(row)
        for expected in case.get('compatibility_assertions', []):
            hit = next((item for item in answer.get('discovered_candidates', []) if item['document_id'] == expected['document_id']), None)
            compatibility.append(hit is not None and hit['compatibility']['criteria'].get(expected['criterion']) == expected['value'])
        for expected in case.get('citation_assertions', []):
            hit = next((item for item in answer['results'] if item['document_id'] == expected['document_id']), None)
            citations.append(hit is not None and expected['page'] in [citation['page'] for citation in hit['citations']])
    metrics = {
        'positive_top1_accuracy': ratio(sum(bool(row['actual_document_ids'] and row['actual_document_ids'][0] in wanted) for row, wanted in positive), len(positive)),
        'positive_recall_at_5': ratio(sum(bool(wanted & set(row['actual_document_ids'])) for row, wanted in positive), len(positive)),
        'negative_control_rejection': ratio(sum(not row['actual_document_ids'] for row in negative), len(negative)),
        'unsupported_conclusion_abstention': ratio(sum(row['abstained'] for row in abstention), len(abstention)),
        'compatibility_accuracy': ratio(sum(compatibility), len(compatibility)),
        'citation_correctness': ratio(sum(citations), len(citations)),
    }
    return {'per_case': rows, 'metrics': metrics}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', required=True, help='Synthetic JSON cases outside production runtime data')
    parser.add_argument('--index', required=True, help='Private local extraction index')
    parser.add_argument('--out', required=True, help='Ignored local result path')
    args = parser.parse_args()
    cases = json.loads(Path(args.cases).read_text())['cases']
    result = evaluate(cases, args.index)
    Path(args.out).write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result['metrics'], indent=2))

if __name__ == '__main__':
    main()
