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
import urllib.error
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

def listing_url(category: str, year: str, limit: int, page: int = 1) -> str:
    return BASE + '?' + urllib.parse.urlencode({'type': category, 'year': year, 'limit': limit, 'page': page})

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

def listing_count(body: bytes):
    """Return the source's declared result count, if the listing supplies one."""
    try:
        meta = json.loads(body).get('meta', {})
        value = meta.get('filter_count')
        return value if isinstance(value, int) and value >= 0 else None
    except Exception:
        return None

def request_listing(url: str):
    status, body, error = None, b'', ''
    try:
        request = urllib.request.Request(url, headers={'Accept': 'application/json', 'User-Agent': 'DRIFT-maritime-notice-research/1.0'})
        with urllib.request.urlopen(request, timeout=30) as response:
            status, body = response.status, response.read()
    except urllib.error.HTTPError as exc:
        status, body, error = exc.code, exc.read(), type(exc).__name__
    except Exception as exc:  # Preserve failed observations in the manifest.
        error = type(exc).__name__
    return status, body, error

def coverage_complete(items, declared_count, errors):
    """A complete first page is not proof of complete annual coverage."""
    return not errors and declared_count is not None and len(items) >= declared_count

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
        started = datetime.now(timezone.utc)
        stamp = started.strftime('%Y%m%dT%H%M%S%fZ')
        stem = unique_stem(root, f'{name}_{args.year}_{stamp}')
        page, declared_count, pages, items, errors = 1, None, [], [], []
        while True:
            url = listing_url(category, args.year, args.limit, page)
            status, body, error = request_listing(url)
            page_items = listing_items(body)
            raw = root / f'{stem}_page{page}.json'
            if body:
                write(raw, body)
            pages.append({'page': page, 'endpoint': url, 'http_status': status, 'raw_file': raw.name if body else '',
                          'sha256': hashlib.sha256(body).hexdigest() if body else '',
                          'schema_valid': page_items is not None, 'record_count': len(page_items) if page_items is not None else None,
                          'error': error})
            if declared_count is None:
                declared_count = listing_count(body)
            if status != 200 or page_items is None:
                errors.append(error or 'invalid_response')
                break
            items.extend(page_items)
            if not page_items or len(items) >= (declared_count if declared_count is not None else len(items)):
                break
            page += 1
        valid = coverage_complete(items, declared_count, errors)
        manifest = {
            'collection_run_id': run, 'source': 'MPA public Media Centre API', 'dataset': name,
            'year': args.year, 'endpoint': listing_url(category, args.year, args.limit), 'retrieval_started_utc': started.isoformat(),
            'retrieval_completed_utc': datetime.now(timezone.utc).isoformat(), 'http_status': pages[-1]['http_status'] if pages else None,
            'raw_file': pages[0]['raw_file'] if len(pages) == 1 else '', 'sha256': pages[0]['sha256'] if len(pages) == 1 else '',
            'schema_valid': not errors, 'valid_coverage': valid,
            'record_count': len(items), 'source_filter_count': declared_count, 'pages': pages, 'error': '; '.join(errors),
            'observation_kind': observation_kind(args.observation_mode, args.year, started),
            'first_observed_utc': started.isoformat(),
            'notice_versions': [version_observation(item) for item in items] if items is not None else [],
        }
        write(root / (stem + '.manifest.json'), (json.dumps(manifest, indent=2) + '\n').encode())
        results.append(manifest)
    print(json.dumps({'run': run, 'requests': len(results), 'valid': sum(x['valid_coverage'] for x in results), 'records': sum(x['record_count'] or 0 for x in results)}))

if __name__ == '__main__':
    main()
