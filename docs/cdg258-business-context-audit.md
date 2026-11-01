# CDG-258: business context audit and blocked integration

CDG-258 is **not implemented or complete**. The required CDG-256 base contains no
shipment-specific business constraints that can substantively extend the AI context.
This change records the audit and a concrete integration contract; runtime code,
API payloads, prompts, validation, and migrations remain unchanged. No placeholder
business context or fabricated default has been added.

## Audited revisions and ownership boundary

- Base: `e87594a6ab2de493c45d4eb3fb6458f0f1d460d0`,
  `feature/CDG-256-persist-display-validated-assessments`, [PR #195](https://github.com/hanlin2025/DRIFT/pull/195).
- CDG-45 decision: [CDG-202](https://csd-drift-g1.atlassian.net/browse/CDG-202).
  Reference-only CDG-203 implementation inspected at
  `ad60e8ce206e4398b4271a202b20d2717117614c` on
  `origin/feature/CDG-203-transshipment-arrival-deadline` (original implementation
  `95bb6ac31f1eea838ccef2da6c652df7bfd356c3`).
- CDG-46 decision: [CDG-205](https://csd-drift-g1.atlassian.net/browse/CDG-205).
  Reference-only CDG-206 implementation inspected at
  `097e333738d4ff3b1492facbde10a657445a0fa5` on
  `origin/feature/CDG-206-delay-comparison`.
- Those implementations are outside this base. Their branches were not modified.
  No CDG-203 implementation, schema, repository, service, or migration is included
  in this branch. The CDG-203 owner must resolve its V28 migration conflict:
  this stack already uses V28 for AI assessments and V29 for validation-status width.

## Fields genuinely available in CDG-256

| Value | Persistence / source of truth | Ownership and meaning | AI treatment |
| --- | --- | --- | --- |
| Transshipment port, mother/feeder vessel name and IMO, planned mother arrival and feeder departure | `shipments`; `Shipment`, `ShipmentService.get`, `ShipmentDetailResponse` | Individual shipment planning data | Already in `MaritimeAiShipmentContext`; preserve existing schedule references. Planned times are not commercial deadlines or observed movements. |
| Shipment reference, origin/destination, vessel MMSI/operator, status, audit/editor/version fields | `shipments`, V3/V4/V7/V20/V21; `Shipment` | Shipment routing, identity, lifecycle and audit data | Do not add to business context: no urgency, cargo sensitivity or commercial constraints can be inferred from them. |
| Managing company and linked importer | `shipments.company_id`, `shipments.importer_company_id` (V8) | Company associations used to authorize access | Authorization inputs only. Do not transmit company/customer identifiers to the provider. |
| Risk email opt-in and minimum risk notification level | `users.risk_email_notifications_enabled`, `users.minimum_risk_notification_level` (V19); `UserAccount`, `ProfileService`, `ProfileView` | Individual user's notification preferences; default minimum HIGH is a notification filter | Exclude. Neither preference defines shipment urgency, company risk tolerance, a deadline, or a cargo property. |
| Name/email/password hash, role, company membership, account status/session/version | `users`; `UserAccount`, `ProfileService` | User identity, authentication and authorization | Exclude unrelated private fields. Role/company are access checks, not business constraints. |
| Company code/name/active flag | `companies`; `Company` | Company identity and availability | No company-level business constraints exist here. Do not derive them from company names. |

The current `Shipment` / `ShipmentDetails` / `ShipmentDetailResponse` models and
migration set contain **no shipment urgency, final-delivery deadline, cargo
sensitivity, inventory quantity, financial exposure, or transshipment-arrival
deadline**. These are unknown/unavailable, not normal urgency, no sensitivity,
no impact, or a deadline equal to a planned movement. Their absence is recorded
explicitly here rather than represented by a new runtime object containing only
unknown values.

The current AI assembly (`MaritimeAiAssessmentService.generate`) calls
`ShipmentService.get` before constructing context. That service resolves the
active company, queries `findByIdVisibleToCompanyId`, and checks
`ShipmentAccessPolicy.canView`. Managing-company and current-linked-importer
visibility apply; unrelated companies receive forbidden/not-found semantics.
Assessment history uses the same shipment gate. None of these rules changes.

## CDG-45/46 reference implementation findings

CDG-203's `ShipmentImporterConstraint` stores one optional
`transshipment_arrival_deadline` with `transshipment_port`, keyed by
`(shipment_id, importer_company_id)` in `shipment_importer_constraints`. Clearing
removes the row. It is a **mother-vessel transshipment-arrival deadline at the
recorded port**, never a final-delivery deadline. There is no urgency or cargo
sensitivity in that contract.

`ShipmentDetailAssembler` looks up only the currently linked importer's row and
projects `TransshipmentArrivalDeadlineResponse(deadline, transshipmentPort,
applicable)` through the normal shipment detail service. The instant is returned
in UTC. Applicability compares stripped current-port text with recorded-port text
exactly; a route edit does not rebind the saved deadline. Former-importer rows
remain stored but hidden until that importer is linked again.

The managing company can view the current row. Only the current linked importer
company can save/clear it on an ACTIVE shipment, independently of permission to
manage that shipment. The update requires confirmation of the current port;
conflicts leave the stored value unchanged. These rules must be reused, not
reimplemented inside AI code.

CDG-206's `DelayComparison` reuses that constraint and the backend
`ArrivalEstimate.estimatedArrivalAt`. It returns unavailable/completed outcomes
and checks milestone compatibility, missing prediction, missing constraint and
staleness. It does not introduce separate inventory/final-delivery constraints or
financial estimates. It is not present in CDG-256 and is not imported here.

## Focused follow-up after owner integration

1. Require an integrated, migration-compatible CDG-203 service on this stack.
   Consume the authorized `ShipmentService.get` detail projection. Do not query
   constraints using a caller-supplied importer/company ID, read arbitrary
   historical rows, or copy a repository/schema into the AI package.
2. Add a minimized typed business-context member containing the arrival deadline,
   recorded port, applicability and explicit provenance (`STORED_IMPORTER_CONSTRAINT`,
   shipment/current-linked-importer ownership, mother-transshipment-arrival milestone).
   Do not send importer/company/user identifiers or confidential cargo descriptions.
3. Distinguish a service unavailable in the deployment from an available service
   with no recorded deadline. Use null values plus an explicit availability/reason
   when needed; never generate a deadline, urgency or sensitivity default. A
   mismatched-port deadline remains recorded but inapplicable to the current port.
4. Introduce a stable canonical business reference, for example
   `business:MOTHER:TRANSSHIPMENT_ARRIVAL_DEADLINE`, only when a stored fact is
   actually supplied. Keep its timestamp, recorded port, applicability and source
   together. Do not mint a maritime `evidenceId` for it. Preserve existing MPA/NEA
   evidence and `schedule:MOTHER:PLANNED_ARRIVAL` /
   `schedule:FEEDER:PLANNED_DEPARTURE` references. No new LLM relationship or impact
   calculation belongs in this follow-up by default.
5. Keep old callers/serialized contexts readable. If business snapshots are ever
   persisted, review historical assessment visibility on importer reassignment:
   shipment visibility alone must not reveal a former importer's stored deadline
   to a newly linked importer. Avoid persisting those snapshots until that rule
   is covered.
6. Verify with offline tests on an isolated database: present/missing/cleared
   values; managing-company and linked-importer reads; unrelated-company denial
   before client invocation; former-importer isolation/relinking; port mismatch;
   UTC instants; canonical references; separation from maritime citations; private
   data minimization; unchanged constraint rows, schedules, risk states/transitions
   and alerts. Retain V9 grounding and all provider approval gates. No real OpenAI
   request is needed.

## What can proceed independently

- Coordinate the CDG-203 migration/service integration with its owner. The blocker
  is missing source data and its service, not an AI prompt or provider issue.
- CDG-259 can independently audit the connection buffer, severity and CDG-208/209
  estimates against actual backend outputs. That work should report which outputs
  exist on its chosen base, their assumptions and freshness, and which observations
  truly enter the calculation. Existing planned schedules and connection/risk
  outputs are operational context, not substitutes for CDG-258 business constraints.
- CDG-260–262 business-specific reasoning/evaluation must retain unknown states
  until real authorized inputs are integrated. They must not infer urgency from
  notification preferences or final delivery from transshipment arrival.

## Validation

Runtime and test source trees are unchanged from CDG-256. A clean offline
regression run and its exact results are recorded below after execution. This
verifies the inherited baseline; it does **not** demonstrate implemented business
constraint retrieval, missing-value behavior, or non-mutation for CDG-203.

Verification on 2026-11-01 (offline Maven `-o`, Java 25):

- Clean combined run: 89 tests, 1 failure, 0 errors, 2 skips. All 85
  grounding/evidence/provider/profile tests passed or skipped (83 passed, 2
  expected skips). The importer-access suite had 3 passes and one list-count
  failure: expected one shipment, found two.
- Diagnosis: the inherited CDG-256 replay test has `@Commit` and its `@BeforeEach`
  creates a shipment before the opt-in assumption skips the replay. That setup
  shipment is committed to the shared test database. The failing importer test
  assumes that company has no other shipments. This is fixture interference in
  unchanged test code, not evidence of authorization leakage.
- Importer-access suite rerun alone on a second empty isolated database: 4 tests,
  0 failures, 0 errors, 0 skips; BUILD SUCCESS. Across the isolated groups,
  87 tests passed and 2 skipped. The initial combined run remains recorded as
  failing; it is not described as an all-green lifecycle.
- Expected skips: opt-in authentic capture replay (no capture supplied), and
  immutable V8 capture validation (private capture absent). No real-provider
  smoke path was enabled. Global external-request approval was explicitly false;
  real-client flags were explicitly false and API-key variables were unset.
- Databases: `drift_cdg203_deadline_20261101000000_258000000001` (empty before
  baseline run; its name came from the abandoned dependency experiment) and
  `drift_cdg258_access_20261101` (empty before importer-only run). Only CDG-256's
  migrations ran. No CDG-203 constraint table/migration was applied. The ordinary
  `drift` and `drift_test` databases were not used for test execution.
- `git diff e87594a -- backend frontend` is empty; the existing local and remote
  CDG-256 branch refs remain at `e87594a`. No source or test changes, no paid API
  request, deployment, PR merge, or Jira-status change.

The selected combined suite was:

```text
*Maritime*,ConfiguredMaritimeAiClientTest,AuthenticMultiSourceGroundingValidationTest,
AuthenticMultiSourceRagIntegrationTest,ShipmentImporterAccessIntegrationTests,
ProfilePreferenceIntegrationTests,UserAccountProfileTest,ProfileApiIntegrationTests
```

Run it with `bash ./mvnw -o clean test -Dtest=<comma-separated selection>` from
`backend`, with `DRIFT_TEST_DATABASE_URL` explicitly pointing to an isolated test
database. Run `ShipmentImporterAccessIntegrationTests` separately on an empty
database until the inherited committed-setup fixture issue is resolved in its own
scope. These are baseline checks only; CDG-258 acceptance tests remain blocked on
substantive business-input integration.
