#!/usr/bin/env python3
"""Append-only collector for public official MPA notice listings.

This is intentionally separate from the DRIFT application. It never downloads
primary PDFs, consumes no credentials, and writes retrieved evidence outside Git.
"""
import argparse
import hashlib
import json
import os
import re
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

def unique_stem(root: Path, stem: str) -> str:
    """Never replace an earlier observation, including two same-second runs."""
    candidate, number = stem, 2
    while (root / (candidate + '.json')).exists() or (root / (candidate + '.manifest.json')).exists():
        candidate = f'{stem}-{number}'
        number += 1
    return candidate

def observation_kind(mode: str, year: str, now: datetime) -> str:
    if mode == 'prospective':
        return 'PROSPECTIVE_OBSERVATION'
    if mode == 'historical-backfill':
        return 'HISTORICAL_BACKFILL'
    return 'HISTORICAL_BACKFILL' if int(year) < now.year else 'OBSERVATION_MODE_UNSPECIFIED'

def version_observation(item: dict) -> dict:
    """Conservatively preserve only relationships explicitly written in a listing."""
    text = ' '.join(str(item.get(key) or '') for key in ('title', 'main_content', 'status'))
    relation = re.search(r'\b(supersedes|amend(?:s|ed|ment to)|withdrawn|cancelled)\b[^\n.]{0,100}', text, re.I)
    status = re.search(r'\b(withdrawn|cancelled|superseded|amended)\b', str(item.get('status') or ''), re.I)
    return {
        'notice_source_id': item.get('id'),
        'title': item.get('title'),
        'publication_value': item.get('release_date'),
        'publication_time_status': 'SOURCE_VALUE_NOT_COLLECTOR_AVAILABILITY_TIME' if item.get('release_date') else 'UNKNOWN',
        'observed_status': status.group(1).upper() if status else 'UNKNOWN',
        'explicit_relationship': relation.group(0).strip() if relation else None,
        'relationship_status': 'SOURCE_BACKED' if relation else 'UNKNOWN',
    }

def listing_items(body: bytes):
    try:
        data = json.loads(body)
        return data['data'] if isinstance(data, dict) and isinstance(data.get('data'), list) else None
    except Exception:
        return None

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--raw-output', default='var/maritime-notices/raw/mpa_official_notices')
    parser.add_argument('--year', default=str(datetime.now().year))
    parser.add_argument('--limit', type=int, default=100)
    parser.add_argument('--observation-mode', choices=('historical-backfill', 'prospective', 'auto'), default='historical-backfill')
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
        items = listing_items(body)
        valid = status == 200 and items is not None
        stamp = started.strftime('%Y%m%dT%H%M%S%fZ')
        stem = unique_stem(root, f'{name}_{args.year}_{stamp}')
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
            'observation_kind': observation_kind(args.observation_mode, args.year, started),
            'first_observed_utc': started.isoformat(),
            'notice_versions': [version_observation(item) for item in items] if items is not None else [],
        }
        write(root / (stem + '.manifest.json'), (json.dumps(manifest, indent=2) + '\n').encode())
        results.append(manifest)
    print(json.dumps({'run': run, 'requests': len(results), 'valid': sum(x['valid_coverage'] for x in results), 'records': sum(x['record_count'] or 0 for x in results)}))

if __name__ == '__main__':
    main()
