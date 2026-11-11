# CDG-255 approved authentic-evidence inference boundary

CDG-255 keeps external inference disabled by default. An authentic retrospective record can reach
the configured OpenAI Responses client only when all technical controls are set:

1. `MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true`;
2. `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_INFERENCE_APPROVED=true`;
3. its exact source type is listed in `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_SOURCE_TYPES_APPROVED`; and
4. its immutable curated ID is listed in `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_EVIDENCE_IDS_APPROVED`.

When global authentic-retrospective approval is disabled, an otherwise sufficient compatible record
is blocked before a provider call. When enabled, a bundle that mixes approved and unapproved records
also abstains before calling a provider; DRIFT does not silently trim it. Recorded replay,
unknown, stale, incompatible, and insufficient evidence remain ineligible. A historical
retrospective ID does not approve a live record.

## Live evidence exact-ID gate

`MARITIME_AI_LIVE_EVIDENCE_IDS_APPROVED` (`app.maritime-ai.live-evidence-ids-approved`) is a
comma-separated list of exact evidence IDs. It is empty by default. `MARITIME_AI_ENABLED` and
`MARITIME_AI_EXTERNAL_REQUESTS_APPROVED` stay separate controls. The OpenAI Responses request still
sets `store:false`.

`MaritimeAiAssessmentService.generate` enforces the list before `interpret`. A sufficient,
compatible, non-stale `LIVE` record reaches the provider only when its evidence ID is listed.
An unlisted sufficient live record abstains the whole bundle as `UNAPPROVED_LIVE_EVIDENCE` and
persists an empty evidence package. Incompatible or insufficient live records do not cause that
abstain and do not become eligible. Approving one fingerprint does not approve a later fingerprint
for the same source identity. `previousValue` on an approved head is part of that one record.

The OpenAI Responses request sends only the bounded `MaritimeAiEvidencePackage` and canonical fact
references. It contains no credentials, raw collector/PDF bodies, database access, company or
customer identifiers, cargo descriptions, shipment reference, origin, destination, or user identity.
The test's minimized planning context is synthetic fixture data required by the typed schedule/risk
contract. Requests explicitly set `store:false`; DRIFT does not retry provider requests. Missing
configuration or approval remains deny-by-default.

## Project-scope owner confirmation

The DRIFT project owner has confirmed approval for project-scope processing through OpenAI of the
two curated historical MPA/NEA records below for this evaluation:

- `mpa-expected-revision-9351048-20261026T154603` (`MPA_ETA_REVISION`)
- `nea-rainfall-20261027T153306` (`NEA_WEATHER`)

This records the project-owner confirmation provided for this work. It does not assert a separate
source-owner approval document, legal finding, or approval for other MPA/NEA records, notices,
collector data, private shipment data, or raw-source content. The technical source-type and exact-ID
allowlists remain required even with project-scope approval. Only the bounded fields and limitations
in the curated records, plus minimized planning context, may be sent. The Responses request must
continue to use `store:false`.

## Real-provider verification status

The current real-provider result, prompt version, validation status, persistence/retrieval result,
and human factual review are recorded in
[`cdg257-authentic-real-provider-verification.md`](cdg257-authentic-real-provider-verification.md).
A response is valid only after JSON-schema parsing, citation membership, typed role/scope checks,
and grounded-claim validation all succeed. Do not weaken the prompt or validator to force acceptance.
