# CDG-262 shared-evidence evaluation

This offline evaluation compares two synthetic shipments that receive the same eligible maritime evidence while the backend computes different risks from their stored schedules. It exercises the existing assessment API, grounding validator, persistence, authorization and CDG-261 presentation. It does not call an AI provider and does not establish how a genuine model will behave in every case.

## Paired shipments

Both inputs use the existing fictional CDG-248/249 MPA and NEA source records, selected by a test-only adapter for the same synthetic port and mother-vessel identity/IMO. The source records, canonical evidence IDs, citations, observation timestamps, values, eligibility and `VERIFIED` association confidence are asserted equal across inputs. The MPA evidence concerns the same mother-vessel arrival revision; the NEA record is regional port-area context. Neither is treated as an input to the risk calculation or as a cause of risk.

The original full replay fixture also pins the feeder departure to one value. That is a scenario guard, not a field in either maritime source record. The pair-specific test selector therefore reuses these fictional records only for the same port/mother identity and the two mother-arrival values accepted by that fixture, while allowing different future feeder departures. This tests downstream interpretation of a shared source bundle; it does not test or change production evidence matching.

| Fact | Shared value |
|---|---|
| MPA evidence | `synthetic-mpa-schedule-001`; synthetic revision citation; observed `2026-10-01T06:00:00+08:00`; previous `05:00`, current `06:00`, recorded change `+60 minutes` |
| NEA evidence | `synthetic-nea-rainfall-001`; synthetic regional-context citation; observed `2026-10-01T05:00:00+08:00`; synthetic rainfall `12.0 mm` |
| Association / eligibility | `VERIFIED`; both records eligible, same IDs, citations, timestamps and values |

The actual shipment risk service evaluates the two stored windows. AIS is disabled in the test profile and the API reports `NO_POSITION`, so no AIS observation is invented or used.

| Shipment | Stored mother arrival → feeder departure | Calculated selected window | Backend result |
|---|---|---|---|
| CDG262-HIGH | Oct 1 05:00 → Oct 1 14:00 (+08:00) | Planned schedule, 9 hours / 32,400 seconds | HIGH; AIS unavailable and not used |
| CDG262-LOW | Oct 1 06:00 → Oct 2 12:00 (+08:00) | Planned schedule, 30 hours / 108,000 seconds | LOW; AIS unavailable and not used |

The capturing stub derives its risk-review wording and structured `RiskReview` from each supplied typed context and emits its canonical references from that input. It is not a pair of prewritten per-shipment outputs.

## Synthetic validated examples

> The documented MPA arrival revision warrants review alongside the backend HIGH planned connection window; the AIS estimate is unavailable.

> The documented MPA arrival revision warrants review alongside the backend LOW planned connection window; the AIS estimate is unavailable.

Both statements are tentative risk-review claims grounded in the same MPA evidence and their own backend risk snapshot. The shared summary keeps evidence and risk separate: “The maritime observations remain separate from the backend connection calculation.” Follow-up asks a person to verify the carrier ETA with the operations team. The output does not assert causation, a missed connection, a delay, a loss, or a final-delivery deadline.

## Acceptance criteria

| Area | Result |
|---|---|
| Same eligible MPA/NEA evidence, distinct actual schedule-derived risk levels, typed contexts and persisted snapshots | Demonstrated by offline API integration tests using the explicit pair-specific synthetic selector described above; production matching is unchanged |
| Context-derived interpretation and existing grounding checks | Demonstrated with a capturing stub; not evidence of general genuine-model behavior |
| Selected-window, estimate-freshness, causal, numerical, constraint, outcome, citation, missing-risk and operational-action rejection | Demonstrated by validator regressions |
| Authorized managing company, current linked importer, former importer and unrelated company access | Demonstrated through the assessment API; denied calls do not invoke the client or disclose evidence/risk references |
| Operational non-mutation | Demonstrated by comparing shipment/risk/alert/change/AIS persistence snapshots and read-computed risk, recommendation and operational-state fields before and after generation |
| Saved assessment snapshots, same evidence and citations in two Shipment Details panels; legacy, failed and abstained display rules | Demonstrated by frontend tests |
| Paired fresh-AIS scenario | Not included: the fixture has no genuine retained AIS observation, and no synthetic AIS was introduced. Existing risk-context tests cover unavailable/stale states and AIS-selected windows separately. |
| Differentiation by urgency, cargo sensitivity or commercial/final-delivery deadline | Blocked by CDG-258 / externally owned CDG-203. `transshipmentArrivalDeadline` is not supplied here and is not a final-delivery deadline. |
| Genuine provider produces appropriate differences across the pair | Not evaluated; no provider request was authorized. |
| Production source retrieval independently matches this pair across feeder-window changes | Not evaluated by this test-only selector. |

CDG-262 therefore demonstrates differentiated interpretation of backend-calculated connection-risk circumstances, but it does not satisfy the blocked business-constraint criterion or complete parent CDG-252.

## Test results

- `MaritimeAiCrossShipmentEvaluationIntegrationTests`: 2 passed, including paired generation/persistence/non-mutation and API authorization/isolation.
- Offline maritime AI and evidence regressions: 62 tests, 0 failures, 0 errors, 2 skipped. This includes the additional validator regression; the skips are capture-dependent cases documented by existing tests.
- `ConfiguredMaritimeAiClientTest`: 7 passed.
- Frontend: 431 tests across 28 files passed; production build passed. Vite emitted its existing large-chunk advisory.
- The same broader batch, including the new validator regression, was rerun against an initially empty database, `drift_cdg262_eval_20261101`: 74 tests, 3 failures, 0 errors, 2 skips. It still failed `ShipmentImporterAccessIntegrationTests.linkedImporterCanViewAndTrackButCannotUpdateWhileManagingForwarderRetainsAccess` (expected one shipment, observed two), `ShipmentRecommendationIntegrationTests.anArchivedShipmentKeepsTheRecommendationAndAnotherCompanySeesNone` (list response included `risk`), and `ShipmentRiskContributionIntegrationTests.aLinkedImporterSeesTheSameContributionsAndAnotherCompanySeesNone` (list response included `risk`). The importer result changed from 29 in the shared database to two in the fresh run. A read of the fresh database found the single extra row was a `CDG249-*` shipment created by `AuthenticMultiSourceRagIntegrationTest`'s setup; its `@Commit`-annotated replay test was skipped because capture replay is opt-in, but the setup row remained. This confirms committed-fixture contamination accounts for the remaining extra importer row. The two list-response failures also reproduced on the fresh database and are not explained by accumulated rows; they need separate investigation. CDG-262's transactional integration tests passed individually.
