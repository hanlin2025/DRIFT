# CDG-288 controlled three-stage scenario clock

This runbook describes the authenticated, manually controlled runtime projection of the CDG-279 synthetic-disruption fixture. The scenario is fictional and read-only. Shipment ETA, connection risk, and business impact are calculated on every snapshot through DRIFT's existing calculation code.

## Audited CDG-282/283/284 contracts

| Contract | Existing shape or service | CDG-288 use |
| --- | --- | --- |
| CDG-282 normalized inputs | `CreateShipmentRequest`, `AisPosition`, `MaritimeEvidence`, explicit fixture visit association | Reuses the versioned fixture and preserves fixture provenance and assumptions. |
| CDG-283 fixture | `cdg279.synthetic-disruption.v1`, two shipment contexts, three ordered raw AIS/evidence stages; calculated outputs are absent from input | Promotes the same validated fixture into runtime source code; expected ETA/risk/impact remain test assertions. |
| CDG-284 risk integration | `ArrivalEstimateCalculator`, `ConnectionWindowService`, `ShipmentRisk.of` | Adds a package-scoped immutable-position/controlled-clock seam; it does not read from or write to `LatestAisPositions`. |
| CDG-284 business impact | `DelayComparison.of` with the recorded transshipment-arrival deadline | Recalculates impact from the estimate and each shipment's fixture deadline for every stage read. |
| CDG-284 maritime context | `MaritimeContextService` evidence bundle and cutoff-based provenance | Uses its existing bundle/provenance mapping after the fixture filters rows by observed and retrieved time. |
| Fictional port gap | The fixture port and its coordinates were test-only because the name is absent from the shared route catalog | Passes the fixture-owned coordinates only through the controlled scenario calculation seam; normal shipment reads still use the shared catalog. |

The CDG-284 integration harness recorded stage AIS into the test database to exercise ordinary APIs. CDG-288 intentionally avoids that write path because it would make simultaneous scenario sessions share global AIS state and could affect live reads.

## Stage and snapshot contract

The public stages map to the validated fixture's three ordered inputs:

| Stage | Fixture stage | Simulation cutoff (UTC) | Mother position / speed | Calculated ETA | Remaining window | Risk |
| --- | --- | --- | --- | --- | ---: | --- |
| `BASELINE` | `baseline` | 2026-10-01 00:00 | 2.26 N / 10 kn | Oct 1 06:00 | 30 h | LOW |
| `CHANGE` | `material-slowdown` | 2026-10-01 04:00 | 1.86 N / 3 kn | Oct 1 16:00 | 20 h | MEDIUM |
| `WORSENED` | `worsened-connection` | 2026-10-01 08:00 | 1.66 N / 1 kn | Oct 2 08:00 | 4 h | HIGH |

The two fixture shipments share a fictional mother-to-feeder visit and 30-hour planned window. Their recorded deadlines remain distinct: shipment A is Oct 1 12:00Z and shipment B is Oct 2 10:00Z. `DelayComparison` calculates the impact for each read. Fixture inputs contain no ETA, risk category, or impact output.

Each response has one `snapshotId`, `simulationCutoffUtc`, and `mapPlaybackCutoffUtc`. Risk freshness, both shipment reads, maritime evidence eligibility, and the optional map clock all use that same stage cutoff. AIS `observedAtUtc`, `receivedAtUtc`, `AisPosition.ingestedAt`, `sourceMode`, and `source` remain separately available as source provenance; the simulated clock does not rewrite them.

## API

All endpoints require a bearer token for an active company. The client creates a UUID for each open scenario session. Sessions are scoped to `(authenticated user, active company, session UUID)`, so separate tabs use separate IDs. A different user or tenant receives `404` for a session they do not own.

### Start or read

```http
PUT /api/scenarios/cdg279/sessions/{sessionId}
Authorization: Bearer <token>
```

Creates the session at `BASELINE`. Repeating Start for an existing session returns its current snapshot without resetting or advancing it.

### Read current snapshot

```http
GET /api/scenarios/cdg279/sessions/{sessionId}
Authorization: Bearer <token>
```

The response contains:

- `sessionId`, `scenarioId`, `contractVersion`, `stage`, `stageOrder`, and `fixtureStageId`;
- the session-scoped `snapshotId` and shared simulation/map cutoffs;
- the two synthetic `shipments`, with planned window, calculated arrival, remaining window, backend-derived `risk`, and `businessImpact`;
- stage AIS observations with original timestamps and provenance; and
- one shared `maritimeContext` bundle filtered at the same cutoff.

The response does not include real shipment database IDs, production recommendations, AI assessments, or decision records.

### Advance

```http
POST /api/scenarios/cdg279/sessions/{sessionId}/next
Authorization: Bearer <token>
Content-Type: application/json

{"expectedSnapshotId":"<snapshotId from the current response>"}
```

`BASELINE` advances to `CHANGE`; `CHANGE` advances to `WORSENED`. Next is guarded by the snapshot the caller saw. If a request is retried with an older snapshot ID from the same session, it returns the current state without advancing again. An unknown or future snapshot ID returns `409 STALE_SNAPSHOT`. Advancing from `WORSENED` returns `409 SCENARIO_COMPLETE` and leaves the session unchanged.

### Reset

```http
POST /api/scenarios/cdg279/sessions/{sessionId}/reset
Authorization: Bearer <token>
```

Reset returns to `BASELINE`. Repeated resets return the same baseline snapshot identity for that session.

Unknown sessions return `404`; unauthenticated requests return `401`; authenticated users without an active company receive `403`; invalid request bodies return `400`.

## Isolation and operation

Session state is a process-local concurrent map containing only the stage index and owner IDs. Updates to one session are serialized, while separate session IDs do not share transition state. Session state is intentionally not written to shipments, AIS observations, importer constraints, alerts, recommendations, decisions, or AI assessment history. A process restart clears sessions; the frontend should create a new session ID and call Start again. Do not depend on a session surviving an application restart or being available on another application node.

The fixture's synthetic port coordinates are passed only to the controlled-snapshot calculation seam. Normal shipment reads still use the shared port catalog. Snapshot calculations use the same `ArrivalEstimateCalculator`, `ConnectionWindowService`, `ShipmentRisk.of`, and `DelayComparison.of` used by shipment reads. The scenario API has no dependency on the maritime AI assessment service or an external model client. Advancing does not submit paid-provider requests.

Maritime evidence is selected only when both `retrievedAtUtc` and any known `observedAt` are at or before the snapshot cutoff. The baseline contains no evidence, `CHANGE` contains only the first synthetic ETA revision, and `WORSENED` adds the stage-two context and its negative controls. No future-stage row is returned early.

## CDG-286 map playback coordination

`mapPlaybackCutoffUtc` is the agreed stage clock for optional playback. CDG-286 exposes recorded track history through `/api/shipments/{id}/track-history` with optional `cutoff` and `captureId`. This CDG-279 fixture has no persisted shipment IDs and its seeded AIS points are not in `vessel_observations` or a reviewed capture. CDG-290 must not treat `shipmentReference` as a shipment ID or pass the fictional MMSIs as though they identified a captured real-vessel track. It may render the two point observations already present in this snapshot, clearly labelled `SYNTHETIC_DEMONSTRATION` / `SEEDED`, or use CDG-286 only after a matching authorized shipment and reviewed capture are available.

No map UI or track-history API changes are part of CDG-288.

## Handoff to CDG-289

1. Generate and retain one UUID per open tab or scenario instance.
2. Call the Start `PUT`, then render the returned `stage`, `shipments`, `maritimeContext`, and cutoff values.
3. On Next, send the current `snapshotId` as `expectedSnapshotId` and replace the whole view from the returned snapshot. Do not locally calculate risk or impact.
4. Disable Next when `nextAvailable` is false. A final-stage conflict is safe to display as an already-complete scenario.
5. Reset by calling the reset endpoint and replacing the view from its response. Do not reuse one session UUID across tabs.

Use `shipmentReference` as the stable synthetic row key. These values are not production shipment IDs.

## Handoff to CDG-290

Read `mapPlaybackCutoffUtc` from the same snapshot and use it as the time label/cutoff for any optional visualization. Keep map position provenance separate from scenario time. The API returns one stage position per vessel, not a historical polyline; a route animation needs a separately reviewed capture that CDG-286 can authorize. Do not infer cargo carriage from the fixture's shared visit assumption.

## Verification

The guarded runner creates a fresh, empty `drift_cdg279_*` PostgreSQL database, checks its ownership marker before Spring starts, and drops only that marked database on exit:

```sh
scripts/test-cdg279-synthetic-disruption.sh
```

The runner covers the session service tests, fixture integrity, and authenticated database integration tests. Its PostgreSQL container must be reachable; if it is unavailable, unit tests can still run directly:

```sh
cd backend
./mvnw -Dtest=ControlledScenarioSessionServiceTest,SyntheticDisruptionScenarioFixtureTest test
```

## CDG-288 test results

- `./mvnw -Dtest=ControlledScenarioSessionServiceTest,SyntheticDisruptionScenarioFixtureTest,ArrivalEstimateTest,ConnectionWindowServiceTest,ShipmentRiskTest,DelayComparisonTest test` — 45 passed, 0 failures/errors/skips.
- `scripts/test-cdg279-synthetic-disruption.sh` — 13 passed, 0 failures/errors/skips against a fresh ownership-marked PostgreSQL 16.15 database; the guarded runner dropped that database after the test.
- `./mvnw -DskipTests compile` — passed.
- `git diff --check` — passed.

The isolated PostgreSQL service was available; there was no database infrastructure blocker.
