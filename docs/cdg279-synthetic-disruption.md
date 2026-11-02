# CDG-279 synthetic disruption replay v1

This runbook describes the test-scope backend scenario implemented for CDG-283 and CDG-284. It exercises DRIFT's authenticated shipment, retained AIS, connection-risk, importer deadline, maritime-context and assessment services. The scenario is fictional; its risk values are calculated by the existing application.

## Verified implementation baseline

- Integration baseline: `integration/cdg252-submission` at `4e5a559b53304deaf3efb7d2f6b1ddbcab692575`.
- CDG-283 fixture commit: `cfe01524a58a56e17400755af16c4c8b5c7b5c5b`.
- Initial CDG-284 replay and acceptance implementation commit: `a02c75468ede616e95d0f7b8f1a2944d7b684026`.
- No production Java, frontend, migration, or CDG-252/CDG-257 files are changed by these implementation commits.

The repository branch was checked through GitHub as well as the isolated local checkout. The fixture branch is based on the verified integration SHA above. CDG-284 is stacked on CDG-283 until the fixture PR is merged.

## Fixture contract and shared itinerary

The versioned contract is `cdg279.synthetic-disruption.v1`. Its inputs wrap the existing `CreateShipmentRequest`, `AisPosition`, and `MaritimeEvidence` shapes. The fixture contains only normalized shipment, observation, evidence, source, visit-association, and stage metadata. ETA, remaining time, risk, business impact, and generated claims are expectations in tests, never fixture response values.

The two authorized shipments share one fixture-assumed mother-to-feeder visit:

- Port: `Synthetic Multi-source Port`, at longitude `103.82`, latitude `1.26` in test configuration only.
- Mother: `CDG279 SYNTHETIC MOTHER <run>`, MMSI `990279001`.
- Feeder: `CDG279 SYNTHETIC FEEDER <run>`, MMSI `990279002`.
- Both schedules: planned mother arrival `2026-10-01T06:00:00Z`, planned feeder departure `2026-10-02T12:00:00Z` (30 hours).
- Shipment A deadline: `2026-10-01T12:00:00Z`.
- Shipment B deadline: `2026-10-02T10:00:00Z`.

The run suffix makes names and references unique. The fixture manifest says both shipments use the same assumed vessel visit; that is not evidence of a real booking or cargo carriage. Source rows and shipment/visit association metadata are kept separate.

All mother observations use longitude `103.82`, `positionValid=true`, status `0`, and course/heading `180`. The stage cutoff equals the report ingestion time. The fixture records this as a zero-ingestion-lag simplification. Feeder observations are retained for role completeness and do not drive arrival risk.

## Calculated scenario stages

These expected values are documented independently in the fixture integration assertions. Detail and list both use the existing `ArrivalEstimateCalculator`, connection-window and risk services; deadline outcomes come from `DelayComparison`.

| Stage | Cutoff and mother observation (latitude / knots) | Calculated ETA | Remaining window | Risk | Shipment A impact | Shipment B impact |
| --- | --- | --- | ---: | --- | --- | --- |
| 0 — baseline | Oct 1 00:00Z, `2.26 / 10` | Oct 1 06:00Z | 30h (`108000s`) | LOW | `NO_PREDICTED_IMPACT` | `NO_PREDICTED_IMPACT` |
| 1 — material slowdown | Oct 1 04:00Z, `1.86 / 3` | Oct 1 16:00Z | 20h (`72000s`) | MEDIUM | `EXCEEDS_DEADLINE` | `NO_PREDICTED_IMPACT` |
| 2 — worsened connection | Oct 1 08:00Z, `1.66 / 1` | Oct 2 08:00Z | 4h (`14400s`) | HIGH | `EXCEEDS_DEADLINE` | `NO_PREDICTED_IMPACT` |

The estimate uses the existing straight-line distance and speed calculation. All three selected bases are `AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE`; the planned connection remains 30 hours. Shipment A's Oct 1 noon deadline is passed by 4 hours at stage 1 and 20 hours at stage 2. Shipment B's Oct 2 10:00 deadline remains 18 hours after the stage 1 estimate and 2 hours after the stage 2 estimate. Neither the notices nor weather changes numeric risk or deadline impact.

These are estimates, not actual-arrival confirmations, missed bookings, or quantified cargo consequences. The model treats planned feeder departure as the comparison boundary and has no extra handling-time allowance.

## Evidence by stage

- Stage 0 has no maritime evidence.
- Stage 1 adds `cdg279-v1-revision-1`, a fictional MPA-style expected-arrival revision from Oct 1 06:00Z to Oct 1 15:00Z, observed and retrieved at the Oct 1 04:00Z cutoff. It is deliberately not substituted for the independently calculated AIS ETA.
- Stage 2 retains revision 1 and adds `cdg279-v1-notice-2` and `cdg279-v1-weather-2`. These are fictional port-area and regional context only. A different-area notice, a stale row, and an unknown-compatibility row are included as negative controls.

Every source is explicitly `SYNTHETIC_DEMONSTRATION`, has a fictional citation and `example.invalid` URL, and preserves its source type, role, scope, compatibility, and review metadata. Stage context filters out future observations. The AI package further excludes incompatible, stale, and unknown-compatibility rows.

## Authenticated assessment and persistence

The integration scenario creates its forwarder/importer identities and both shipments through the normal authenticated endpoints, links the importer, saves its deadlines through the importer deadline endpoint, and records AIS using `LatestAisPositions.record` with `AisPositionSource.SEEDED`. It does not insert calculated risk or impact rows.

Stage 0's two assessment requests return `ABSTAINED` / `INSUFFICIENT_EVIDENCE`; the offline client is not called. Stages 1 and 2 return four generated assessments in total, all `VALID`. The test-only `SyntheticDisruptionAiClient` reads typed backend facts and eligible source rows and runs through the unchanged production `MaritimeAiResponseValidator`. Its provider label is `offline-synthetic-context-stub`, which the existing frontend panel classifies as a controlled synthetic stub rather than a provider-generated interpretation if this assessment shape is reused later. No OpenAI request is made. The two shipment histories each retain the three stage snapshots, and history retrieval confirms their saved risk, deadline, comparison, and evidence facts after later stages and a deadline edit.

The test also generates one additional successor-owned assessment after reassignment. Existing assessment history is shipment-scoped, however: a newly linked importer can read prior snapshots containing the previous importer's deadline. The current assessment schema has no importer-visibility boundary. Former importer access to current shipment detail, context, generation, and history is denied, and only the current importer can edit the deadline, but old assessment business snapshots remain visible to a successor. Fixing that requires an authorization/schema change and is outside this test-scope implementation.

The suite verifies 401 for unauthenticated API calls, 403 for unrelated or former importers, no offline-client invocation or assessment write for denied generation, identical eligible evidence for both shipments, and no changes to a sentinel shipment, saved schedules/deadlines, recommendations, risk transitions, or alert deliveries.

## Boundaries covered

The integration suite also checks no mother position, absent SOG, SOG below `0.5` knots, unknown port, absent deadline, a deadline no longer applicable to the current port, exactly six-hour-old AIS, and AIS one microsecond beyond six hours. It checks the existing unavailable reasons and planned-window fallback; it does not create a remaining-window value when the AIS estimate cannot be used.

The fixture ledger rejects backward stages and conflicting duplicate stage records, treats an identical duplicate as a no-op, validates IDs/provenance/times/coordinates, and prevents future evidence from appearing in earlier stage contexts.

## Run and cleanup

From the repository root, run:

```sh
scripts/test-cdg279-synthetic-disruption.sh
```

The runner requires the repository PostgreSQL container on port 5432. It creates a new `drift_cdg279_<UTC timestamp>_<random>` database, marks it with a random ownership comment, and exports that database as the test datasource and Flyway target. `SyntheticDisruptionDatabaseGuard` runs before Spring context refresh and requires the `test` profile, matching datasource/Flyway credentials, a new empty database, ordered migrations, disabled AIS/alerts/runtime demo and external AI approval, and blank provider/model/endpoint/API-key settings. The offline client is the only assessment client used by the test. On exit, the runner drops only the database whose ownership comment still matches. Set `KEEP_CDG279_DB=1` only when intentionally retaining a failing run for inspection.

The plan-listed backend regression suite was run with separate fresh, marked databases for the arrival-comparison, importer-deadline, and delay-comparison guarded tests, plus an isolated fresh database for the remaining classes:

```text
ArrivalEstimateTest, ConnectionWindowServiceTest, ShipmentRiskTest,
RemainingConnectionWindowScenarioTest, ArrivalComparisonIntegrationTests,
ShipmentConstraintIntegrationTests, DelayComparisonTest,
DelayComparisonIntegrationTests, ShipmentRecommendationIntegrationTests,
PortConditionComparisonIntegrationTests, SyntheticMultiSourceMaritimeFixtureTest,
MaritimeAiSyntheticMultiSourceIntegrationTests,
MaritimeAiCrossShipmentEvaluationIntegrationTests,
MaritimeAiConnectionRiskContextTest, MaritimeAiResponseValidatorTest,
ConfiguredMaritimeAiClientTest
```

Results: the new fixture/integration runner passed 8 tests (7 contract tests and 1 authenticated integration test) on two consecutive fresh databases, then passed again after the startup guard was tightened. The plan-listed regression classes passed 143 tests with 1 existing skipped test and 0 failures.

## Frontend and persistent-demo status

The existing authenticated shipment detail page already has generic maritime-context and AI-assessment panels and calls the authorized context, assessment-generation, and assessment-history APIs. That does not expose this scenario: the contract, three-stage ledger, AIS/evidence adapter, and fictional port coordinates are test-scoped; the test database is dropped; production has no loader or persistent evidence source for these IDs; and the page has no control to advance these stages. This is not a completed frontend demonstration.

The minimum additional work for a persistent demonstration is to provision an isolated demo dataset and stage state in a nonproduction environment, supply runtime coordinates for the fictional port (or change the scenario to an existing catalog port), and provide a runtime, cutoff-aware synthetic evidence adapter and stage-advance orchestration that reuses this normalized contract. The authenticated shipment detail view can then display the seeded shipments and generated assessments. If the fixed October timestamps are retained, the app also needs a scenario-scoped controlled clock; otherwise the persistent seed must rebase its schedule and observation times to the demonstration run. A frontend stage selector can be added in CDG-281 after the persistent backend source and authorization shape are agreed.
