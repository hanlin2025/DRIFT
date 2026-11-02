# CDG-285: real vessel selection and AIS capture runbook

This runbook gives an operator a repeatable way to choose a container-vessel candidate for each demonstration role and export a bounded track from DRIFT's existing AIS ingestion. Select actual ships nearer the demonstration date. No vessel names, identifiers, or observations are preselected here.

A mother candidate and feeder candidate are two display/tracking candidates only. Selecting both does **not** establish a booking, shared voyage, port visit, transshipment, or cargo relationship.

## Existing DRIFT AIS path

- `AisStreamConnectionService` connects to the configured AISStream WebSocket only when `AIS_ENABLED=true`. It subscribes to `PositionReport` inside the configured bounding box. The provider key is sent by the existing service; the exporter below never connects to the provider and never reads or prints that key.
- `AisPositionParser` rejects malformed messages, non-nine-digit MMSIs, and coordinates outside latitude/longitude ranges. It maps unavailable SOG/COG/heading values to null. `ais_utc_second` is only the AIS UTC second-of-minute (60-63 and other unavailable values become null), not a full event timestamp.
- A parsed report gets `ingested_at` from DRIFT's UTC clock and source `AIS_STREAM`. `LatestAisPositions` keeps an in-memory latest view and persists the observation in `vessel_observations`. A persistence error is logged and that report is skipped; the stream continues. The table also uses `SEEDED` for seeded observations.
- `/api/shipments/{id}/tracking` returns the latest retained fix for shipment vessel names. `/api/shipments/{id}/track-history` is shipment-authorized and limited to 6, 24, or 72 hours and 500 points; it omits `position_valid=false` rows. It is useful for the app contract but is not an export for an unlinked candidate.
- The existing `RouteMap` draws its planned route from the shipment's port fields, overlays latest fixes from `/tracking`, and draws a selected vessel's points/segments from `/track-history`. Live pins are labelled from their `AIS_STREAM`/`SEEDED` source. It is not an AIS playback interface; CDG-285 adds no map or map API contract.
- `tools/ais_capture/capture_export.py` reads the existing `vessel_observations` table for one exact MMSI, source `AIS_STREAM`, and inclusive UTC receipt-time window. It does not add ingestion, alter risk calculations, write observations, or expose an API. The export preserves invalid/unknown position flags and missing optional values for review.

## 1. Select and verify candidates

Complete a separate checklist for the mother and feeder candidates. Recheck near demo day; do not keep vessel IDs in source code or treat an old candidate as guaranteed available.

- [ ] **Name and type:** Record the vessel's registered name, aliases, and vessel type from an authoritative vessel or flag-state record. Confirm it is a container vessel; note why it is being used as the mother or feeder display candidate.
- [ ] **IMO and MMSI:** Verify the seven-digit IMO (including its check digit) and nine-digit MMSI against an authoritative vessel registry and cross-check the provider's AIS vessel metadata. Record the source/reference and check time. Do not infer identity from a name match, MMSI prefix, or one AIS report alone.
- [ ] **Singapore relevance:** Confirm recent AIS positions are in the Singapore Strait, Port of Singapore, or the approaches relevant to this demonstration. Record the evidence and time window. A Singapore-area bounding-box match alone does not prove a port call or berth.
- [ ] **Observation frequency and coverage:** Make a short scout export first. Review first/last receipt times, median report interval, total report count, valid-position count/span, and the maximum gap between valid positions. For this demo runbook, a candidate normally needs at least five valid positions over 20 minutes and no unexplained gap over 10 minutes in the chosen review window. If a vessel is stationary or reports more slowly, document the reason and have the operator explicitly accept the shorter coverage; do not silently interpolate.
- [ ] **Position and motion quality:** Review coordinates and `position_valid`; use only positions marked `true` for position claims. `false` rows remain in the export and are flagged. Treat a null validity flag as unknown. Do not treat missing SOG as zero/stopped and do not invent values for missing speed, course, heading, or AIS seconds.
- [ ] **Name consistency:** Compare AIS-reported name variants with the verified name and aliases. The export flags unexpected names; resolve a mismatch before using the track. DRIFT's shipment tracking lookup is name-based, with only its documented `MV`/`M/V` prefix handling.
- [ ] **Source, retention, and replay permission:** Confirm the connected AIS provider/account terms allow the planned capture, local retention, and demonstration replay. Record a review reference and date, permitted purpose, and delete-by date. If capture/retention is unclear, do not export authentic reports. If replay is not explicitly permitted, do not use the authentic capture for playback; rehearse with the fictional fixture instead.
- [ ] **Relationship boundary:** Record that these are independently selected vessel candidates. Do not claim a real booking, voyage leg, or mother-to-feeder transshipment unless separate authorized evidence establishes it.

The script's `NO_REPORTS`, `FLAGS_PRESENT`, and `NO_AUTOMATED_FLAGS` values are summaries, not approval decisions. Every manifest leaves `operator_acceptance` as `PENDING`; the operator records the selection decision separately.

## 2. Prepare the existing feed and database access

1. Ask the operator responsible for the backend to enable the existing AISStream connection and configure its bounding box to cover the relevant Singapore area. Keep `AIS_ENABLED` off when no capture is planned. Supply the provider key only through the approved secret/environment mechanism used by the backend; never put it in the runbook, command line, exported manifest, fixture, or Git.
2. Confirm the backend is storing recent `AIS_STREAM` observations. The capture tool uses the backend database directly and requires the PostgreSQL command-line client (`psql`) plus the usual `PGHOST`/`PGDATABASE`/`PGUSER` or `PGSERVICE` settings. Prefer a read-only database role and the approved local PostgreSQL password/service-file mechanism. No provider credentials are needed by `psql`.
3. Choose a bounded start and end time in ISO-8601 with an explicit offset. The tool converts them to UTC, includes both endpoints, and rejects windows longer than 72 hours. Use a short scout interval, then a separately named capture interval after the candidate passes review.
4. Store authentic exports only in an approved private local directory. The example below uses `local/ais-captures/`; this directory is Git-ignored. Keep the CSV and its manifest together, restrict access, and delete them by the recorded date or sooner if permission is withdrawn.

## 3. Export each candidate

Run once for the mother candidate and once for the feeder candidate. Substitute the operator-verified identity, evidence references, review times, window, retention date, and output path. The values below are placeholders, not real vessel identities.

```bash
python3 tools/ais_capture/capture_export.py \
  --role mother \
  --vessel-type container \
  --vessel-name 'VERIFIED VESSEL NAME' \
  --imo 'REPLACE_WITH_VERIFIED_IMO' \
  --mmsi 'REPLACE_WITH_VERIFIED_MMSI' \
  --identity-reference 'registry/source record reference' \
  --identity-checked-at '2026-11-02T10:00:00+08:00' \
  --from '2026-11-02T10:00:00+08:00' \
  --to '2026-11-02T10:30:00+08:00' \
  --terms-reference 'provider terms review reference' \
  --terms-reviewed-at '2026-11-02T09:00:00+08:00' \
  --replay-permission UNKNOWN \
  --retain-until '2026-11-09' \
  --output 'local/ais-captures/<run-id>/mother.csv'
```

Repeat with `--role feeder`, the feeder's own verified identity, and a separate output path. The script writes a CSV plus `<stem>.manifest.json`. The manifest records the selected identity evidence reference, provider-usage review, exact time bounds, export time, SHA-256, source attribution, quality counts, and retention date. It contains no provider key. Set replay permission to `APPROVED` only when the documented review explicitly permits it; otherwise record `NOT_APPROVED` or `UNKNOWN` and do not replay authentic data.

The script requires a valid nine-digit MMSI and a valid seven-digit IMO checksum before querying. It filters the database to the exact MMSI, `AIS_STREAM`, and time window, and orders reports by receipt time and observation id. It does not query or export `SEEDED` rows. An empty result still produces a header-only CSV and a manifest with `NO_REPORTS`, then exits with status 3 so an operator can distinguish it from a successful non-empty export. A missing `psql` client or unavailable database returns a clear error and does not substitute fixture data.

## 4. Review, reject, or replace candidates

- Confirm the manifest's MMSI, selected name, IMO, source reference, bounds, and retention/replay fields against the checklist.
- Compare the reported AIS names, Singapore-area coordinates, valid-position count, timestamps, median interval, and maximum report/valid-position gaps. `GAP_OVER_10_MINUTES`, invalid/unknown position flags, missing AIS name/speed/UTC second, or name mismatch require review. If there are no reports, wrong-area positions, unresolved identity/name differences, or unusable coverage, reject or replace the candidate and repeat with a new output path.
- Keep gaps as gaps. Do not synthesize reports, interpolate positions, convert missing speed to zero, or relabel the fictional fixture as a real capture. The configured consumer only stores messages it receives, so an export cannot establish complete provider coverage.
- To replace either real vessel, repeat the checklist and command with the replacement name, IMO, MMSI, and identity evidence. Update the shipment's existing mother/feeder vessel fields using the current form/import/API, including optional IMO and MMSI fields. No application source change is needed. Recheck the exact AIS-reported name because shipment position matching is name-based.
- Store only the approved export and manifest outside tracked source. Do not add authentic tracks to this repository or CI fixtures. Delete the files by the manifest's retention date.

## Offline rehearsal fixture

Use [`fixtures/cdg285/fictional_ais_track.csv`](../fixtures/cdg285/fictional_ais_track.csv) when the provider or database is unavailable. Its two clearly named `CDG285 FICTIONAL` tracks use `SEEDED` and fixture-only MMSIs `990285001` and `990285002`; values are fabricated Singapore-area examples. It contains intentionally missing optional fields. It is not output from the exporter and is not an authentic AIS record. The fixture and its limitations are described in [`fixtures/cdg285/README.md`](../fixtures/cdg285/README.md).

## Limitations and handoff to CDG-286

- DRIFT stores receipt time, not a complete AIS event timestamp. `ais_utc_second` alone cannot reconstruct the report's event time. Replay must label this limitation and must not present receipt time as provider event time.
- The consumer persists normalized position fields, not the raw provider payload or an authoritative IMO/MMSI registry crosswalk. Identity evidence remains a manual operator responsibility.
- Tracks can be incomplete because of provider coverage, configured bounding-box limits, feed downtime, invalid-message rejection, or storage errors. A capture is the observations DRIFT received in the requested window, not a complete voyage history.
- The exporter does not modify the shipment, route map, risk semantics, or frontend. No real ships are selected in this change.
- CDG-286 can consume the manifest/CSV contract for playback. It will need an explicit playback-time contract given receipt-time semantics, retention/permission checks before using authentic captures, clear source labels separating `AIS_STREAM` from `SEEDED`, gap/missing-value behavior without interpolation, and bounded controls. This change does not implement playback.
