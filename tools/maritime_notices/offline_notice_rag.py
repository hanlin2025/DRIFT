#!/usr/bin/env python3
"""Private local PDF extraction and deterministic notice discovery.

Input PDFs and extracted index files belong under ignored runtime storage. This
tool never downloads, uploads, or commits document content.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from notice_compatibility import assess, document_activities, locations, unsupported_conclusion

PRIVATE = Path('var/maritime-notices/private')
STOP = {'the', 'and', 'for', 'with', 'from', 'that', 'this', 'are', 'was', 'what', 'where', 'when', 'does', 'which', 'who', 'notice', 'port', 'marine', 'no', 'pmn', 'vessel', 'eta', 'delay', 'at', 'to', 'of', 'in', 'on'}

def tokens(text):
    return [item for item in re.findall(r"[a-z][a-z0-9]{1,}", text.lower()) if item not in STOP]

def pages_from_pdf(path):
    try:
        result = subprocess.run(['pdftotext', '-layout', str(path), '-'], capture_output=True, text=True, timeout=60)
        if result.returncode:
            return None, result.stderr.strip() or 'pdftotext failed'
        return result.stdout.split('\f'), ''
    except Exception as exc:
        return None, type(exc).__name__

def facts(page_text):
    text = '\n'.join(page_text)
    flat = re.sub(r'\s+', ' ', text)
    result = {'notice_identifier': None, 'effective_period': None, 'coordinates': [], 'precautions': [], 'scope': None, 'supersedes': None, 'unknowns': [], 'fact_pages': {}}
    match = re.search(r'(?:NOTICE|PMN)\s*(?:NO\.?\s*)?(\d+\s+OF\s+\d{4})', text, re.I)
    result['notice_identifier'] = match.group(1) if match else None
    match = re.search(r'(?:Date\s*:|effect from)\s*([^\n]{4,120})', text, re.I)
    result['effective_period'] = match.group(1).strip() if match else None
    result['coordinates'] = re.findall(r'\d{1,2}°\s*\d{1,2}\.\d+[′\']?\s*[NS]?\s*\d{2,3}°\s*\d{1,2}\.\d+', flat)
    result['precautions'] = [item.strip() for item in re.findall(r'(?:Keep well clear|Maintain[^\n]+|Proceed at[^\n]+|not to enter[^\n]+)', text, re.I)]
    match = re.search(r'Applicable to\s*:\s*([^\n]+)', text, re.I)
    result['scope'] = match.group(1).strip() if match else None
    match = re.search(r'supersedes\s+(?:Port Marine Notice )?No\.?(?:\s*)([^.\n]+)', text, re.I)
    result['supersedes'] = match.group(1).strip() if match else None
    for key in ('effective_period', 'scope'):
        if not result[key]:
            result['unknowns'].append(key)
    for key, value in result.items():
        if key in ('unknowns', 'fact_pages') or not value:
            continue
        values = value if isinstance(value, list) else [str(value)]
        result['fact_pages'][key] = sorted({index + 1 for item in values for index, page in enumerate(page_text) if re.sub(r'[^a-z0-9]', '', item.lower()) in re.sub(r'[^a-z0-9]', '', page.lower())})
    return result

def extract(pdf, source_url, document_id, output):
    pages, error = pages_from_pdf(pdf)
    if pages is None:
        return {'ok': False, 'error': error}
    source = Path(pdf)
    data = {'document_id': document_id, 'source_url': source_url, 'local_pdf': source.name,
            'pdf_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'pages': [{'page': index + 1, 'text': page} for index, page in enumerate(pages) if page.strip()]}
    data['facts'] = facts([page['text'] for page in data['pages']])
    output.mkdir(parents=True, exist_ok=True)
    (output / (document_id + '.json')).write_text(json.dumps(data, indent=2) + '\n')
    return {'ok': True, 'pages': len(data['pages'])}

def local_pdf_hash(data, private_root=PRIVATE):
    path = private_root / 'pdfs' / data.get('local_pdf', '')
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def discover(index, question, limit=5, private_root=PRIVATE):
    requested = Counter(tokens(question))
    requested_numbers = set(re.findall(r'\b\d{1,3}\b', question))
    hits, seen_hashes = [], set()
    for path in sorted(Path(index).glob('*.json')):
        data = json.loads(path.read_text())
        content_hash = data.get('pdf_sha256') or local_pdf_hash(data, private_root)
        if content_hash and content_hash in seen_hashes:
            continue
        if content_hash:
            seen_hashes.add(content_hash)
        score, citations = 0, []
        for page in data.get('pages', []):
            overlap = sum((Counter(tokens(page['text'])) & requested).values())
            if overlap:
                citations.append({'page': page['page'], 'matched_terms': sorted(set(tokens(page['text'])) & set(requested)), 'score': overlap})
                score += overlap
        identifier = data.get('facts', {}).get('notice_identifier') or ''
        if requested_numbers & set(re.findall(r'\b\d{1,3}\b', identifier)):
            score += 3
            citations.append({'page': data.get('facts', {}).get('fact_pages', {}).get('notice_identifier', [1])[0], 'matched_terms': ['notice_identifier'], 'score': 3})
        if score:
            first_page = (data.get('pages') or [{}])[0].get('text', '')[:1200]
            hits.append({'document_id': data['document_id'], 'source_url': data['source_url'], 'score': score, 'facts': data.get('facts', {}), 'document_activities': sorted(document_activities(first_page)), 'document_locations': sorted(locations(first_page)), 'citations': citations})
    hits.sort(key=lambda item: item['score'], reverse=True)
    return hits[:limit]

def query(index, question, limit=5, private_root=PRIVATE):
    if unsupported_conclusion(question):
        return {'query': question, 'discovered_candidates': [], 'results': [], 'abstain': True, 'unsupported_reason': 'Official notices cannot establish vessel- or ETA-specific causation.', 'limitation': 'Results are source facts only; no vessel/ETA causation or impact is inferred.'}
    discovered = discover(index, question, 10_000, private_root)
    assessed = [{**hit, 'compatibility': assess(question, hit)} for hit in discovered]
    results = [hit for hit in assessed if hit['compatibility']['decision'] != 'REJECT']
    results.sort(key=lambda hit: (sum(value == 'COMPATIBLE' for value in hit['compatibility']['criteria'].values()), hit['score']), reverse=True)
    return {'query': question, 'discovered_candidates': assessed, 'results': results[:limit], 'abstain': not bool(results), 'limitation': 'Results are source facts only; no vessel/ETA causation or impact is inferred.'}

def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='command', required=True)
    extraction = commands.add_parser('extract')
    extraction.add_argument('--pdf', required=True)
    extraction.add_argument('--source-url', required=True)
    extraction.add_argument('--document-id', required=True)
    extraction.add_argument('--out', default=str(PRIVATE / 'extracted'))
    search = commands.add_parser('query')
    search.add_argument('--index', default=str(PRIVATE / 'extracted'))
    search.add_argument('question')
    args = parser.parse_args()
    result = extract(args.pdf, args.source_url, args.document_id, Path(args.out)) if args.command == 'extract' else query(args.index, args.question)
    print(json.dumps(result, indent=2))
    sys.exit(0 if result.get('ok', True) else 2)

if __name__ == '__main__':
    main()
