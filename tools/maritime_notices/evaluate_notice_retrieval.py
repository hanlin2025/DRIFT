#!/usr/bin/env python3
"""Evaluate a caller-supplied synthetic case file; never ships real evidence."""
import argparse
import importlib.util
import json
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('rag', Path(__file__).with_name('offline_notice_rag.py'))
rag = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rag)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cases', required=True, help='Synthetic JSON cases outside production runtime data')
    parser.add_argument('--index', required=True, help='Private local extraction index')
    parser.add_argument('--out', required=True, help='Ignored local result path')
    args = parser.parse_args()
    cases = json.loads(Path(args.cases).read_text())['cases']
    results = []
    for case in cases:
        answer = rag.query(args.index, case['query'])
        results.append({'id': case['id'], 'expected': case['expected'], 'actual_document_ids': [item['document_id'] for item in answer['results']], 'abstained': answer['abstain']})
    Path(args.out).write_text(json.dumps({'results': results}, indent=2) + '\n')

if __name__ == '__main__':
    main()
