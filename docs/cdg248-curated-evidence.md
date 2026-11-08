# CDG-248 curated MPA and NEA evidence

`backend/src/main/resources/maritime-context/cdg248-curated-evidence.json` is a deliberately
small, immutable, read-only export of two authentic collection records. It does not contain raw
API bodies, credentials, or collector paths. Each item retains its original source identifier,
manifest/raw reference, source event time, collector retrieval time, and source mode.

The export is generated from the standalone CDG-201 revision and weather manifests, but DRIFT
only reads the packaged resource at runtime. To refresh it, an approved maintainer must make a
new curated export from checksum-verified standalone manifests, review its provenance and
licensing, and commit the new resource. The application never reads a developer home directory.

Set `MARITIME_CONTEXT_DEMO_ENABLED=true` and
`MARITIME_CONTEXT_CURATED_SNAPSHOTS_ENABLED=true` only for the replay demonstration. Curated
records are always marked `RETROSPECTIVE_ANALYSIS` / `HISTORICAL_REPLAY`; a past source event
does not become point-in-time evidence merely because it is present in this resource. Eligibility
requires the record's UTC retrieval timestamp to be at or before the assessment cutoff.

## External AI processing status

This export is available to the DRIFT application, but that is **not** approval to transmit it to
an external AI provider. There is currently no committed approval record for either curated item.
Authentic retrospective inference therefore remains default-deny and must not be enabled until an
authorised owner records, for each exact evidence ID, its source terms/attribution, permitted
external processor, retention conditions, and approval date.

The application enforces three separate gates before an authentic record can reach a provider:

1. `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_INFERENCE_APPROVED=true`;
2. the source type in `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_SOURCE_TYPES_APPROVED`; and
3. the exact curated evidence ID in `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_EVIDENCE_IDS_APPROVED`.

The ID allowlist is deliberately record-specific: approving `MPA_ETA_REVISION` does not approve a
future MPA export, and likewise for NEA. These settings are necessary technical controls, not a
substitute for source-owner approval.
