# CDG-259 risk and connection-buffer context

## Source audit at CDG-256 `e87594a`

`ShipmentService.get` first resolves the active user's company and applies existing shipment visibility. `MaritimeAiAssessmentService.generate` assembles AI context only from that authorized `ShipmentDetailResponse`. The new `MaritimeAiConnectionRiskContext` maps only backend outputs on that response:

- `connectionWindow` is the planned mother-arrival to feeder-departure interval. It is available only for a positive interval. Missing schedule inputs are `INCOMPLETE`; a non-positive interval is `INVALID` and has no fabricated duration.
- `remainingConnectionWindow` is present only when a usable, non-stale AIS-based mother-vessel arrival estimate and planned feeder departure exist. It retains `AVAILABLE` and `MISSED`, including exact remaining or missed seconds. Missing, invalid, stale or unavailable estimates never produce a buffer.
- Severity is the existing `ShipmentRisk.level`. Its `riskBasis` follows `riskContributions`: `AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE` when the remaining window produced risk, otherwise `PLANNED_SHIPMENT_SCHEDULE`. An unassessed risk has no severity or basis.
- Freshness and assumptions come from the same `arrivalComparison` and `assessmentExplanation`: current/stale/unavailable/unknown, position receipt time where available, unavailable reason code, and existing backend assumptions. No AIS coordinates, identifiers, provider data, formal recommendation, or operational-state decision is added.

The existing estimate is produced by `ArrivalEstimateCalculator` from the latest retained mother-vessel AIS position, straight-line distance to the transshipment port, and usable reported speed; positions strictly older than six hours are stale and speeds below 0.5 knots are unavailable. The remaining buffer and severity use that estimate only when `ShipmentConnectionRisk` and `ConnectionWindowService` select the remaining window. Otherwise severity falls back to the planned window with its estimate-not-used reason. The V9 grounding gates remain intact. A `RISK_BUFFER_REVIEW` claim must cite eligible maritime evidence and canonical severity plus selected-window facts. The prompt and structured-output schema version advances to V10/V9 respectively to explain the added typed context while retaining existing grounding instructions and provider approvals.

Canonical references are added only for present values: `risk:CONNECTION_SEVERITY`, `risk:PLANNED_CONNECTION_WINDOW`, `risk:REMAINING_CONNECTION_WINDOW`, `risk:AIS_ESTIMATED_ARRIVAL`, and `risk:ARRIVAL_ESTIMATE_NOT_USED`. Existing schedule and MPA/NEA evidence references are preserved. An MPA historical ETA revision or NEA observation remains retrieved evidence; neither is an input to this risk engine.

## CDG-208/209 reference-only audit

Jira CDG-208 defines a separate chance of arrival after planned feeder departure based on the straight-line point estimate and an uncalibrated normal-distribution assumption. Its interim handling threshold is zero; this does not establish cargo-transfer time or a confirmed missed connection. CDG-209 exposes that as `LateArrivalChance`, calculated on read with distinct unavailable reasons and the explicit “rough estimate” label. Those commits remain on another branch and are not part of CDG-256; no code or schema was copied. This context does not carry a chance/probability. The prompt explicitly forbids inference of one. If CDG-209 is later integrated, it needs a separate typed field with its assumptions, freshness and uncalibrated provenance; it must not be substituted for severity or the remaining buffer.

## CDG-260 dependencies

CDG-260 can now cite these canonical backend risk facts alongside independently grounded MPA/NEA evidence. It must preserve the distinction between retrieved evidence, deterministic computed risk and human considerations, and reject invented causal, numeric, probabilistic or operational claims. Business deadline/urgency inputs remain dependent on CDG-258's owner-coordinated integration of CDG-203; this PR does not make that dependency disappear. Any claim about why evidence matters to a specific shipment must continue to treat missing business constraints as unknown.

## Separate inherited test-fixture issue

`AuthenticMultiSourceRagIntegrationTest.explicitlyApprovedCaptureReplayPersistsForLocalShipmentDetailsDemonstration` is annotated `@Test @Commit`; its setup creates a shipment before the opt-in assumption skips when no capture path is configured. The committed setup shipment can leak into later tests sharing a database; this caused the prior importer-access list-count failure. Track a focused fixture fix separately (check the opt-in assumption before committed setup, or avoid committing skipped setup). No test or implementation change for this issue is included here.
