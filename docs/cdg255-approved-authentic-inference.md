# CDG-255 approved authentic-evidence inference boundary

CDG-255 keeps external inference disabled by default. An authentic retrospective record can
reach the configured OpenAI Responses client only when all technical controls are set:

1. `MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true`;
2. `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_INFERENCE_APPROVED=true`;
3. its exact source type is listed in `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_SOURCE_TYPES_APPROVED`; and
4. its immutable curated ID is listed in `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_EVIDENCE_IDS_APPROVED`.

If a sufficient, compatible authentic retrospective bundle mixes an approved record with an
unapproved record, DRIFT abstains before calling a provider. It does not silently trim the bundle
and send a potentially misleading partial context. Recorded replay, live, unknown, stale,
incompatible, and insufficient evidence remain ineligible.

The OpenAI Responses request sends the bounded `MaritimeAiEvidencePackage` and canonical fact
references only. It contains no credentials, raw collector/PDF bodies, database access, company,
customer, cargo, shipment reference, origin, destination, user identity, or provider key. The
minimal schedule context is retained because the V9 `SCHEDULE_REVIEW` contract requires its
canonical references. Requests explicitly set `store:false`; DRIFT does not add retries.

## Approval still required

No source-owner authorization for authentic MPA OCEANS-X arrival records or NEA data.gov.sg
weather records is committed. On 2026-11-01, the DRIFT team confirmed that project-scope
transmission of the two curated IDs below to OpenAI is permitted:

- `mpa-expected-revision-9351048-20261026T154603`
- `nea-rainfall-20261027T153306`

The confirmation is limited to relevant retrospective observations, the bounded payload, the
OpenAI Responses provider, explicit `store:false`, and the exact type/ID gates above. It does not
authorize other MPA, NEA, notice, collector, private-shipment, or raw-source data. Before a real
request, DRIFT must still retain the responsible source-owner/organizational approval record with
source terms, permitted processor and fields, attribution, retention/deletion conditions,
approver, and date. An environment flag is a technical control, not that approval record.

## Real-provider readiness

The retained prior real-response captures used earlier contracts and include validator rejections;
they are not evidence of a V9-valid assessment. The V9 prompt/validator should not be relaxed to
make a model response pass. Use the existing separately approved synthetic-only smoke-test path
for one reproducible provider check after explicit authorization. Do not use that path with MPA or
NEA evidence. A provider response is `VALID` only after schema parsing, canonical-reference
membership, typed role/scope checks, and grounded-claim validation succeed.
