# CDG-258 business context audit and integration outcome

Status as of 2026-11-02: CDG-258's authorized transshipment-deadline context is
implemented in the consolidated `integration/cdg252-submission` branch. The
historical audit in PR [#196](https://github.com/hanlin2025/DRIFT/pull/196)
was accurate for its CDG-256 base, but its “blocked” conclusion predates the
CDG-203 importer-constraint integration and this branch's business-context
work. CDG-258 does not establish cargo sensitivity, inventory, financial
exposure, final-delivery deadlines, or urgency; those values remain unknown.

## Authorized source and minimized projection

- The source is the optional `transshipment_arrival_deadline` and recorded port
  in `shipment_importer_constraints` (V30), owned by the current linked
  importer. It is a mother-vessel transshipment-arrival deadline, not a final
  delivery deadline.
- Assessment generation first calls `ShipmentService.get`, which applies the
  existing shipment visibility policy. `MaritimeAiBusinessContext.from` only
  projects the authorized `ShipmentDetailResponse`; it does not query by a
  caller-supplied company ID or expose importer/company identifiers.
- Missing and unavailable states remain explicit. A deadline saved for a
  different port remains visible with `applicableToCurrentPort=false`; no
  planned movement is substituted for a missing business deadline.
- The deterministic `DelayComparison` outcome and reason are included as typed
  context. The AI response validator accepts only claims supported by the
  supplied deadline/comparison facts and canonical references. Business facts
  do not become maritime evidence citations.
- The deterministic summary is stored with the assessment and served from its
  historical snapshot. Current shipment values are not substituted when an
  older assessment lacks that snapshot. Shipment access checks also protect
  history after importer reassignment.

## Implementation and regression coverage in #202

- Context assembly and immutable model: `MaritimeAiAssessmentService` and
  `MaritimeAiBusinessContext`.
- Canonical fact references and claim validation:
  `MaritimeAiCanonicalFactReferences` and `MaritimeAiResponseValidator`.
- Deadline lifecycle, port applicability, UTC handling and importer isolation:
  `ShipmentConstraintIntegrationTests`.
- Assessment-time context, history snapshots, former/unrelated importer denial,
  missing or mismatched deadlines, and no client invocation on denied access:
  `MaritimeAiCrossShipmentEvaluationIntegrationTests`.
- Claim-level behavior for unsupported deadlines, unavailable comparisons and
  business references: `MaritimeAiResponseValidatorTest`.
- Shipment Details rendering of the assessment-time context:
  `frontend/src/shipment/ShipmentDetail.test.tsx` and
  `frontend/src/shipment/MaritimeAiAssessmentPanel.test.tsx`.

The historical CDG-256 `@Commit` capture-replay test also exposed fixture writes
before its opt-in assumption. The consolidated branch now checks replay
approval and an explicitly named isolated `cdg256_demo_` database in `@BeforeEach`
before fixture writes. `VerifiedCaptureReplayClientTest` covers owner-only file
permissions, exact approved citation IDs, and a `VALID` OpenAI Responses capture
using offline temporary fixtures. Those fixtures are not evidence of a real
provider capture and make no external request.

## Limits and migration order

The audit supports only the facts described above; it does not claim a
shipment-specific urgency or cargo profile exists. Any future expansion needs
an authorized source, explicit unavailable states, importer-reassignment
isolation, minimized provider input, immutable history, and offline regression
coverage before the model can interpret those facts.

The current sequence is V29 recommendation decisions, V30 importer constraints,
and V31 maritime AI validation-status widening. The separate CDG-272 PR #203
uses V32; it must not be merged or deployed before #202's V31 is merged and
applied.

## Consolidated branch validation on 2026-11-02

- Backend focused unit and integration batch: 79 tests, 0 failures, 0 errors,
  2 expected capture-dependent skips; BUILD SUCCESS on PostgreSQL 16 with the
  schema at V31.
- Frontend: 463 tests across 30 files passed; the production build passed with
  Vite's existing large-chunk advisory.
- No provider request was made. The reviewed V9-valid provider capture remains
  unverified; offline replay tests use synthetic temporary fixtures only.
