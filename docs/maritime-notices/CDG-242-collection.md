# CDG-242: Official maritime-notice collection and provenance

`tools/maritime_notices/mpa_official_notices_collect.py` is an optional,
append-only research collector for three official MPA listing categories. It
records the response bytes, retrieval timestamps, endpoint, HTTP status,
schema outcome, and SHA-256 checksum in an adjacent manifest.

It does not download PDFs, use credentials, schedule itself, update DRIFT, or
infer a publication time from the retrieval time. Runtime evidence is written
below `var/maritime-notices/`, which is ignored by Git.

Run manually only after confirming applicable MPA terms and access conditions:

```bash
python3 tools/maritime_notices/mpa_official_notices_collect.py --year 2026
```

This branch deliberately contains no retrieved listings, documents, logs, or
credentials.
