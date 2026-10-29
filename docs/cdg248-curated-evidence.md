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
