# CDG-201 MPA data pipeline

This is a curated, version-controlled copy of the CDG-201 MPA source code.
The operational scripts remain authoritative at
`~/opt/data/change_detection/tools/`; do not replace, move, or schedule this
copy. No raw responses, `.env` files, credentials, Windows task registrations,
or runtime logs belong in this directory.

All scripts use Python's standard library. Unit tests use injected responses or
temporary directories and do not make network calls.

## Commands

```bash
# Run the no-network tests from the repository root.
python3 -m py_compile tools/cdg201/mpa/*.py tools/cdg201/mpa/tests/*.py
PYTHONPATH=tools/cdg201/mpa python3 -m unittest discover \
  -s tools/cdg201/mpa/tests -p 'test_*.py' -v

# Manually collect snapshots only with a local, untracked .env file.
python3 tools/cdg201/mpa/mpa_oceansx_collect.py \
  --env /secure/path/.env --rolling --feed-group expected \
  --output /secure/data/raw/mpa_oceansx

# Derive provenance-preserving, offline reports from existing snapshots.
python3 tools/cdg201/mpa/detect_mpa_expected_revisions.py \
  --root /secure/data/raw/mpa_oceansx --output /secure/data/analysis/revisions
```

The collector records UTC retrieval timestamps and retains source timestamp
strings without inventing a timezone. The detector compares only distinct,
validated collection runs, preserves ambiguity, and does not treat an expected
movement as an actual port or berth arrival.

`normalize_mpa_reported_events.py` and `pair_mpa_reported_visits.py` are
offline helpers. A reported arrival/departure pair is not proof of a
container-terminal berth visit.
