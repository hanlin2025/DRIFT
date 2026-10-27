# CDG-240 AI-readiness monitoring

This is the version-controlled source counterpart of the active offline
monitoring utilities in `~/opt/data/change_detection/tools`. Do not move or
replace the active files: Windows scheduled collectors use that workspace.

The workflow is offline and follows this mandatory order:

1. regenerate MPA ETA/ETD revisions from validated expected-feed snapshots;
2. build prospective ETA-to-reported-arrival candidates;
3. inspect point-in-time weather availability;
4. calculate the ETA-persistence baseline and audit matched labels;
5. append a daily readiness snapshot.

It stops at a failed stage and records the failure separately. It never turns
missing data into a negative label or treats a stale report as fresh. The
daily trend preserves an existing legacy schema and starts
`daily_readiness_trend_v2.csv` where the current result schema differs.

MPA source event values are interpreted as Asia/Singapore; retrieval values
remain UTC. Raw source strings, manifests, and checksums are preserved. A
reported event must occur after the prediction cutoff as well as be retrieved
after it to become an evaluation label.

Set `CDG201_DATA_ROOT` to the external data root when executing these offline
utilities. No command here contacts an API. The revision detector is supplied
by the CDG-231–233 MPA pipeline at `tools/cdg201/mpa/`; this branch does not
duplicate it.

```bash
export CDG201_DATA_ROOT=/secure/cdg201-data
PYTHONPATH=tools/cdg201/contextual:tools/cdg201/ai_readiness \
  python3 -m unittest discover -s tools/cdg201/ai_readiness/tests -p 'test_*.py' -v
```
