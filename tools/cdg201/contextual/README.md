# CDG-201 contextual data and AI-readiness tools

This directory is a curated, version-controlled source copy. The operational
workspace at `~/opt/data/change_detection/` remains authoritative for active
collection, raw evidence, Windows Task Scheduler wrappers, and local `.env`
credentials. Do not replace or relocate it from this branch.

Included tools provide:

- append-only NEA weather collection with provenance and pagination/resume;
- optional MPA declaration collection and offline integrity/snapshot checks;
- leakage-aware MPA ETA-to-reported-arrival maturation;
- point-in-time MPA/NEA inspection joins and ETA-persistence evaluation.

No source here automatically makes API calls during tests. It contains no API
keys, raw responses, scheduler registrations, runtime logs, or datasets.

## Data contract and branch relationship

This branch has no Git commit dependency on the Group A branch. At runtime,
the readiness utilities accept paths to MPA manifest/raw-snapshot directories
and revision CSV outputs produced using Group A's documented manifest format.
That is a data-contract dependency, not a stacked PR requirement.

MPA expected timestamps retain their source strings because timezone semantics
are undocumented. Historical NEA backfills remain retrospective evidence and
are excluded from leakage-safe feature joins.

## Local validation

```bash
python3 -m py_compile tools/cdg201/contextual/*.py tools/cdg201/contextual/tests/*.py
PYTHONPATH=tools/cdg201/contextual python3 -m unittest discover \
  -s tools/cdg201/contextual/tests -p 'test_*.py' -v
```

## Example offline commands

```bash
python3 tools/cdg201/contextual/build_eta_ground_truth_candidates.py \
  --mpa-root /secure/data/raw/mpa_oceansx \
  --revisions /secure/data/analysis/revisions/expected_revisions.csv \
  --output /secure/data/analysis/ai_readiness

python3 tools/cdg201/contextual/build_mpa_nea_inspection_join.py \
  --revisions /secure/data/analysis/revisions/expected_revisions.csv \
  --weather-root /secure/data/raw/nea_weather \
  --output /secure/data/analysis/ai_dataset_readiness
```

An unmatched reported arrival is never made a negative outcome. ETA-persistence
metrics are unavailable until a prospective ETA has a confidently matched,
later reported-arrival label.
