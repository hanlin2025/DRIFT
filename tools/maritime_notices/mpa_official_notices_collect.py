#!/usr/bin/env python3
"""Append-only collector for public official MPA notice listings.

This is intentionally separate from the DRIFT application. It never downloads
primary PDFs, consumes no credentials, and writes retrieved evidence outside Git.
"""
import argparse
import hashlib
import json
import os
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

CATEGORIES = {
    'port_marine_notices': '2b89298e-3d17-4bf2-8275-9079e84f63d0',
    'port_marine_circulars': '0b4c161c-92d5-475e-8a41-51e096406f74',
    'singaporean_notices_to_mariners': '81f14c08-938c-4998-980e-01b435c77658',
}
BASE = 'https://www.mpa.gov.sg/api/items/media_releases_and_circulars'

def write(path: Path, body: bytes) -> None:
    """Atomically create one immutable observation file."""
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_bytes(body)
    os.replace(temporary, path)

def listing_url(category: str, year: str, limit: int) -> str:
    return BASE + '?' + urllib.parse.urlencode({'type': category, 'year': year, 'limit': limit, 'page': 1})

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--raw-output', default='var/maritime-notices/raw/mpa_official_notices')
    parser.add_argument('--year', default=str(datetime.now().year))
    parser.add_argument('--limit', type=int, default=100)
    args = parser.parse_args()
    root = Path(args.raw_output)
    root.mkdir(parents=True, exist_ok=True)
    run = 'mpa-notices-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    results = []
    for name, category in CATEGORIES.items():
        url = listing_url(category, args.year, args.limit)
        started = datetime.now(timezone.utc)
        status = None
        body = b''
        error = ''
        try:
            request = urllib.request.Request(url, headers={'Accept': 'application/json', 'User-Agent': 'DRIFT-maritime-notice-research/1.0'})
            with urllib.request.urlopen(request, timeout=30) as response:
                status, body = response.status, response.read()
        except Exception as exc:  # Preserve failed observations in the manifest.
            error = type(exc).__name__
        try:
            data = json.loads(body)
            items = data['data'] if isinstance(data, dict) and isinstance(data.get('data'), list) else None
        except Exception:
            items = None
        valid = status == 200 and items is not None
        stamp = started.strftime('%Y%m%dT%H%M%SZ')
        stem = f'{name}_{args.year}_{stamp}'
        raw = root / (stem + '.json')
        if body:
            write(raw, body)
        manifest = {
            'collection_run_id': run, 'source': 'MPA public Media Centre API', 'dataset': name,
            'year': args.year, 'endpoint': url, 'retrieval_started_utc': started.isoformat(),
            'retrieval_completed_utc': datetime.now(timezone.utc).isoformat(), 'http_status': status,
            'raw_file': raw.name if body else '', 'sha256': hashlib.sha256(body).hexdigest() if body else '',
            'schema_valid': items is not None, 'valid_coverage': valid,
            'record_count': len(items) if items is not None else None, 'error': error,
        }
        write(root / (stem + '.manifest.json'), (json.dumps(manifest, indent=2) + '\n').encode())
        results.append(manifest)
    print(json.dumps({'run': run, 'requests': len(results), 'valid': sum(x['valid_coverage'] for x in results), 'records': sum(x['record_count'] or 0 for x in results)}))

if __name__ == '__main__':
    main()
