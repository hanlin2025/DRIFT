#!/usr/bin/env python3
"""Append-only prospective collector for MPA arrival/departure declarations."""
import argparse, hashlib, json, os, random, sys, time, urllib.error, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

def load_env(path):
    for line in Path(path).read_text().splitlines() if Path(path).exists() else []:
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1); os.environ.setdefault(key.strip(), value.strip().strip('"'))

def atomic_write(path, body):
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_bytes(body); os.replace(tmp, path)

def fetch(url, key, attempts, pause):
    history = []
    for attempt in range(1, attempts + 1):
        started = datetime.now(timezone.utc)
        status, body, headers, error = None, b'', {}, ''
        try:
            request = urllib.request.Request(url, headers={'apikey': key, 'Accept': 'application/json'})
            with urllib.request.urlopen(request, timeout=30) as response:
                status, body, headers = response.status, response.read(), dict(response.headers.items())
        except urllib.error.HTTPError as exc:
            status, body, headers, error = exc.code, exc.read(), dict(exc.headers.items()) if exc.headers else {}, f'HTTPError:{exc.code}'
        except Exception as exc: error = type(exc).__name__
        completed = datetime.now(timezone.utc)
        history.append({'attempt': attempt, 'retrieval_started_utc': started.isoformat(), 'retrieval_completed_utc': completed.isoformat(), 'http_status': status, 'error': error})
        retryable = status == 429 or (status is not None and 500 <= status <= 599) or (status is None and error)
        if not retryable or attempt == attempts: return status, body, headers, error, history
        retry_after = headers.get('Retry-After')
        try: delay = max(float(retry_after), 0) if retry_after else pause * (2 ** (attempt - 1))
        except ValueError: delay = pause * (2 ** (attempt - 1))
        time.sleep(delay + random.uniform(0, min(1.0, delay / 10)))

def parse_array(body):
    try: parsed = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError): return None
    return parsed if isinstance(parsed, list) else None

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--env', default='.env', help='Untracked local credentials file')
    parser.add_argument('--output', default='data/external/maritime/raw/mpa_declarations')
    parser.add_argument('--days', type=int, default=3, help='SGT today and this many minus one prior dates')
    parser.add_argument('--max-retries', type=int, default=4)
    parser.add_argument('--retry-pause-seconds', type=float, default=2)
    args = parser.parse_args(); load_env(args.env)
    key, base = os.getenv('MPA_OCEANSX_API_KEY'), os.getenv('MPA_OCEANSX_API_BASE', '').rstrip('/')
    if not key or not base: sys.exit('Missing MPA_OCEANSX_API_KEY or MPA_OCEANSX_API_BASE in local .env.')
    root = Path(args.output); root.mkdir(parents=True, exist_ok=True)
    run_id = 'mpa-declarations-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    today = datetime.now(ZoneInfo('Asia/Singapore')).date()
    results = []
    for kind in ('arrival', 'departure'):
        for offset in range(args.days):
            query_date = (today - timedelta(days=offset)).isoformat()
            started = datetime.now(timezone.utc)
            endpoint = base + f'/api/v1/vessel/{kind}declaration/bydate?' + urllib.parse.urlencode({'date': query_date})
            status, body, headers, request_error, attempts = fetch(endpoint, key, args.max_retries, args.retry_pause_seconds)
            parsed = parse_array(body) if body else None
            schema_valid = parsed is not None
            error = request_error
            if status == 204: error = error or 'HTTPNoContent'
            elif status is not None and not 200 <= status < 300: error = error or f'HTTPError:{status}'
            elif not schema_valid: error = error or 'MalformedOrNonArrayJSON'
            valid_coverage = bool(status == 200 and schema_valid)
            completed = datetime.now(timezone.utc); stamp = started.strftime('%Y%m%dT%H%M%S%fZ')
            stem = f'{kind}_declaration_{query_date}_{stamp}'; raw_path = root/(stem + '.json')
            if body: atomic_write(raw_path, body)
            manifest = {'collection_run_id': run_id, 'dataset': f'{kind}_declaration', 'queried_date': query_date,
                        'retrieval_started_utc': started.isoformat(), 'retrieval_completed_utc': completed.isoformat(),
                        'endpoint_without_credentials': endpoint, 'http_status': status, 'response_body_present': bool(body),
                        'schema_valid': schema_valid, 'valid_coverage': valid_coverage, 'empty_valid_result': bool(valid_coverage and not parsed),
                        'record_count': len(parsed) if schema_valid else None, 'raw_file': raw_path.name if body else '',
                        'sha256': hashlib.sha256(body).hexdigest() if body else '', 'error': error, 'attempts': attempts,
                        'response_rate_limit_headers': {k: v for k, v in headers.items() if k.lower() in ('retry-after','x-ratelimit-limit','x-ratelimit-remaining','x-ratelimit-reset')}}
            atomic_write(root/(stem + '.manifest.json'), (json.dumps(manifest, indent=2) + '\n').encode())
            results.append(manifest)
    print(json.dumps({'collection_run_id': run_id, 'requests': len(results), 'valid_coverage': sum(r['valid_coverage'] for r in results), 'failed': sum(not r['valid_coverage'] for r in results), 'records': sum(r['record_count'] or 0 for r in results)}))

if __name__ == '__main__': main()
