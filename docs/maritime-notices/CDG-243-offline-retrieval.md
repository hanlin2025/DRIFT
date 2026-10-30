# CDG-243: Offline retrieval and citation extraction

`tools/maritime_notices/offline_notice_rag.py` is a local-only seam for
permission-cleared PDFs held outside Git. It extracts page-separated text using
the local `pdftotext` program, records structured facts and page references,
and performs deterministic keyword discovery.

It does not download or upload documents, transmit document text, determine a
notice's operational applicability, or infer vessel/ETA impact. Its input and
derived index belong under ignored `var/maritime-notices/private/` storage.

The tool intentionally emits source facts and citations only. CDG-244 adds the
separate compatibility and abstention layer.
