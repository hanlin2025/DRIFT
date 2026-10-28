# CDG-242: Official maritime-notice collection and provenance

`tools/maritime_notices/mpa_official_notices_collect.py` is an optional,
append-only research collector for three official MPA listing categories. It
records the response bytes, retrieval timestamps, endpoint, HTTP status,
schema outcome, SHA-256 checksum, observation mode, and conservative
source-backed version observations in an adjacent manifest.

It does not download PDFs, use credentials, schedule itself, update DRIFT, or
infer a publication time from the retrieval time. Publication values remain
separate from first-observed retrieval time, and unavailable status/relationship
evidence is `UNKNOWN`. Runtime evidence is written
below `var/maritime-notices/`, which is ignored by Git.

Run manually only after confirming applicable MPA terms and access conditions:

```bash
python3 tools/maritime_notices/mpa_official_notices_collect.py --year 2026
```

Use `--observation-mode prospective` only for an approved ongoing observation;
the default labels a run as historical backfill. The collector does not download
primary PDFs. PDF acquisition and content-hash deduplication remain separate,
private research tooling rather than listing-collection scope.

This branch deliberately contains no retrieved listings, documents, logs, or
credentials.
