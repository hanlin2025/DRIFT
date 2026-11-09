# CDG-286: recorded AIS track retrieval and playback contract

This change extends the shipment-authorized track-history endpoint for deterministic, read-only cutoff retrieval. It does not select actual ships, create an AIS ingestion path, or add playback controls to the map. The two vessels remain independent display candidates; this endpoint does not establish a booking, shared voyage, cargo association, or transshipment relationship.

## Data already available and reused

- `AisStreamConnectionService`, `AisPositionParser`, `LatestAisPositions`, and `vessel_observations` remain the only live AIS ingestion and persistence path. Live AIS observations are not copied into a second table for playback.
- The CDG-285 capture exporter reads bounded `AIS_STREAM` observations from that same table and writes a CSV plus sidecar manifest. CDG-286 verifies those files when authentic cutoff playback is requested; it does not import the CSV or update the live-position cache.
- `GET /api/shipments/{id}/track-history` remains the only retrieval route. It applies the existing shipment visibility check before resolving vessel identity or reading a capture. There is no unrestricted MMSI endpoint.
- Shipment IMO and MMSI fields already exist. Set the selected vessel identities on the shipment using the existing detail form, import, or shipment API; replacing either actual candidate requires data changes only, not an application source change.

## Endpoint contract

Existing requests remain supported:

```http
GET /api/shipments/123/track-history?vessel=MOTHER&hours=24
Authorization: Bearer <shipment-authorized token>
```

For deterministic offline rehearsal, include a receipt-time cutoff and omit `captureId`. This mode returns only `SEEDED` observations for the selected shipment vessel MMSI and refuses the request if `AIS_STREAM` records exist for that MMSI in the preceding 72 hours.

```http
GET /api/shipments/123/track-history?vessel=MOTHER&hours=6&cutoff=2026-10-30T12%3A01%3A00Z
Authorization: Bearer <shipment-authorized token>
```

For authentic playback, add the UUID from a reviewed CDG-285 manifest. The shipment must have matching IMO and MMSI values for that role.

```http
GET /api/shipments/123/track-history?vessel=FEEDER&hours=24&cutoff=2026-10-30T12%3A01%3A00Z&captureId=8d09f294-4e7c-47c0-a32b-3df2294072f5
Authorization: Bearer <shipment-authorized token>
```

`vessel` is `MOTHER` or `FEEDER`, defaulting to `MOTHER`. `hours` is `6`, `24`, or `72`, defaulting to `24`. The window start is exclusive and its end/cutoff is inclusive. A cutoff must be ISO-8601 with an explicit UTC offset and cannot be in the future. The API rejects unsupported windows, malformed/future cutoffs, missing or mismatched shipment identifiers for an authentic capture, unavailable capture files, a mismatched CSV digest/schema, an incompatible capture, unaccepted captures, missing replay permission, and expired retention. Requests still return 404 for shipments the caller cannot view.

Example response fields:

```json
{
  "vessel": "MOTHER",
  "vesselName": "MV selected vessel",
  "mmsi": "563123456",
  "windowHours": 6,
  "from": "2026-10-30T06:01:00Z",
  "to": "2026-10-30T12:01:00Z",
  "cutoff": "2026-10-30T12:01:00Z",
  "timestampSemantics": "DRIFT_RECEIPT_TIME",
  "context": "RECORDED",
  "status": "STALE",
  "feedStatus": "OFFLINE",
  "fallbackUsed": false,
  "captureId": "8d09f294-4e7c-47c0-a32b-3df2294072f5",
  "points": [
    {
      "id": 184467,
      "latitude": 1.264,
      "longitude": 103.82,
      "speedOverGroundKnots": null,
      "courseOverGroundDegrees": 175.0,
      "trueHeadingDegrees": null,
      "ingestedAt": "2026-10-30T12:00:30Z",
      "source": "AIS_STREAM",
      "positionValid": null
    }
  ]
}
```

The values above illustrate the contract only; they are not authentic AIS records. `ingestedAt` is when DRIFT received/stored the report, not its provider event time. The stored `ais_utc_second` is only a second-of-minute value and is not used to reconstruct event time. Missing speed/course/heading remain null; `positionValid=false` rows and out-of-range coordinates are omitted, while unknown position validity remains visible. Distinct observation IDs sharing one timestamp are retained and ordered by ID. No points are interpolated.

Responses include at most 500 points. If more observations are available, the newest 500 in the requested window are returned and `truncated=true`. `lastKnownPosition` is the latest retained point not flagged invalid at or before the cutoff, within the 72-hour bound; a null `positionValid` remains unknown. When the requested window is empty but that point is outside it, `fallbackUsed=true`; clients must label it as a last-known fallback rather than a playback frame. `status` is `UNAVAILABLE` without a usable point, `OFFLINE` when the global feed is offline, `STALE` when the last point is over one hour before the requested time, and otherwise `AVAILABLE`. `feedStatus` reports the current global feed and does not change historical track contents. `context` distinguishes `LIVE_POSITION`, `RECORDED`, `SEEDED`, `MIXED`, and `UNAVAILABLE` provenance.

For authentic captures the backend reads the manifest and CSV beneath `AIS_CAPTURE_DIRECTORY` (Spring property `app.ais.capture-directory`), verifies the exact CDG-285 source/classification, role, shipment IMO/MMSI, timestamp semantics, capture time bounds, provider review fields, retention date, operator acceptance, CSV header, row scope, positions, unique observation IDs, and SHA-256 digest. CSV source must be `AIS_STREAM`. The accepted manifest is an operator-controlled local record, not a provider-signed authorization token; the SHA-256 check binds its CSV but does not independently establish that the provider terms review is truthful or legally sufficient. Keep the directory private and follow the review/delete-by record.

## Operator capture sequence

Follow the vessel identity, coverage, Singapore relevance, position-quality, and permitted-retention checklist in [the CDG-285 runbook](cdg285-ais-vessel-selection-and-capture.md). Select/recheck each real candidate near demo day; do not add vessel names or identifiers to source code. Save one bounded capture and manifest for each role. The two candidates do not imply a relationship.

Before playback:

1. Confirm the shipment stores the matching IMO and MMSI under the correct role. The playback route is still scoped to that shipment and role.
2. Confirm `AIS_CAPTURE_DIRECTORY` points at the private capture parent containing the manifest/CSV pairs. Keep each pair adjacent; do not copy authentic files into the repository or CI fixture area.
3. Review the manifest's vessel identity reference, provider usage/retention review, observation coverage, gaps, coordinates, missing fields, and CSV digest. Playback requires `replay_permission=APPROVED`, `operator_acceptance=ACCEPTED`, and `retain_until` not earlier than the current UTC date. If any check is pending or unclear, playback rejects the capture.
4. Request a fixed cutoff at or before the capture end. To switch candidates, update the shipment's role identity, select that role's capture ID, and use its own bounded cutoff. No application source changes are needed.
5. If the capture directory is unavailable or connectivity is down, use the fictional fixture for development rehearsal. The actual service does not replace an unavailable authentic capture with fixture data.

There is no authentic capture checked into the repository, and no real two-vessel set has been selected or exported for this change. Authentic playback is therefore conditional on the operator completing the CDG-285 checklist and providing a retained, reviewed capture pair.

## Offline rehearsal fixture

The existing [`fixtures/cdg285/fictional_ais_track.csv`](../fixtures/cdg285/fictional_ais_track.csv) remains clearly fictional and contains two `SEEDED` sample MMSIs. It is the fixture covered by the cutoff integration test. To rehearse against a local development database, load it manually through PostgreSQL after creating a shipment with mother MMSI `990285001` and feeder MMSI `990285002`; do not run this against shared or production data:

```sql
CREATE TEMP TABLE cdg285_fixture_import (
  fixture_id text, mmsi text, vessel_name text, latitude numeric, longitude numeric,
  speed_over_ground_knots numeric, course_over_ground_degrees numeric, true_heading_degrees integer,
  navigational_status integer, position_valid boolean, ais_utc_second integer,
  ingested_at_utc timestamptz, source text
);
\copy cdg285_fixture_import FROM 'fixtures/cdg285/fictional_ais_track.csv' WITH (FORMAT csv, HEADER true)
INSERT INTO vessel_observations (mmsi, vessel_name, latitude, longitude, speed_over_ground_knots,
  course_over_ground_degrees, true_heading_degrees, navigational_status, position_valid,
  ais_utc_second, ingested_at, source)
SELECT mmsi, vessel_name, latitude, longitude, speed_over_ground_knots,
  course_over_ground_degrees, true_heading_degrees, navigational_status, position_valid,
  ais_utc_second, ingested_at_utc, source
FROM cdg285_fixture_import
WHERE source = 'SEEDED';
```

Then call the seeded example above at a cutoff such as `2026-10-30T12:01:00Z`. The SQL ignores the fixture's text observation IDs so the database assigns its own unique IDs. Fixture observations are not authentic and do not establish a vessel relationship.

## CDG-287 map handoff

CDG-287 can call this existing shipment-scoped endpoint with a selected vessel, window, and fixed cutoff, then render the returned coordinates as `[longitude, latitude]` in the existing map. Preserve the server point order; use `source`, `context`, `status`, `cutoff`, and `fallbackUsed` to label the trace and any last-known marker. Keep playback frames at the cutoff, do not use live `/tracking` results as historical frames, and do not interpolate across missing reports. CDG-286 adds only optional API parsing support in the frontend; it does not alter `RouteMap` or introduce playback controls.

## Limitations

- A capture contains only messages DRIFT received in its bounded interval. Provider gaps, bounds, feed downtime, parse rejects, or storage errors can make it incomplete.
- Receipt time is not AIS event time. This contract is deterministic for DRIFT receipt-time history only.
- Local manifest review values are operator supplied and unsigned. The service verifies shape and consistency but cannot independently verify identity registry claims or the provider contract.
- Track retrieval is read-only. It does not update shipment fields, latest-position cache, AIS observation rows, risk calculations, ETAs, alerts, or recommendations.
