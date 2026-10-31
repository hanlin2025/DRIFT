# CDG-247 real-model smoke test

CDG-247 remains disabled by default. The only direct-provider protocol implemented is
`openai-responses`, which uses the OpenAI Responses API with a strict JSON schema for
`MaritimeAiInterpretation`. The existing `approved-gateway` protocol remains available for
a separately approved gateway that accepts the bounded evidence package and returns that
interpretation JSON directly.

## Required local configuration

Use a non-production, project-restricted test key in the environment of the backend process;
never place it in source control, a frontend variable, a curl command, or an application log.

```bash
export MARITIME_AI_ENABLED=true
export MARITIME_AI_PROVIDER=openai-responses
export MARITIME_AI_MODEL='gpt-4.1-mini'
export MARITIME_AI_ENDPOINT='https://api.openai.com/v1/responses'
export MARITIME_AI_API_KEY='<test-only key>'
export MARITIME_AI_TIMEOUT_SECONDS=20
export MARITIME_AI_MAX_OUTPUT_TOKENS=800
export MARITIME_CONTEXT_DEMO_ENABLED=false
```

`JWT_SECRET` is also required by the normal local backend startup procedure in the root
README. The backend sends only the `MaritimeAiEvidencePackage`: contract version, cutoff,
association-confidence flag, and CDG-246 evidence metadata. It does not send database access,
collector directories, raw PDFs, credentials, or a shipment record.

`MARITIME_AI_TIMEOUT_SECONDS` must be 1–60 and `MARITIME_AI_MAX_OUTPUT_TOKENS` must be
64–4096. An invalid configuration fails before any provider request. The client does not retry
provider calls; repeat only through a separately approved smoke-test run.

For a normal backend runtime (not this test-only fixture),
`MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true` is additionally required. The smoke test has a
separate, stricter `CDG247_RUN_REAL_SMOKE=APPROVED` gate and cannot be enabled through normal
application configuration alone.

## Mandatory evidence gate

Do **not** run this against the current KOTA LARIS replay: its MPA revision is recorded
evidence, not a synthetic bundle. A real call through a normal backend runtime remains
blocked because it has no production-selectable synthetic source. The explicitly approved
test command below instead injects a test-scope synthetic-only fixture. Do not work around
this boundary by sending a real shipment or MPA replay externally.

The unit test `ConfiguredMaritimeAiClientTest` is the offline protocol harness. It proves
the request shape, strict-schema request fields, structured-output envelope parsing,
generic-gateway compatibility, and malformed/refusal/incomplete/HTTP-error/timeout behavior
without opening a network connection. `MaritimeAiSyntheticSmokeIntegrationTests` uses the
same authenticated POST/GET endpoints and JPA persistence path with a test-scope fixture and
stub client. The fixture exists under `src/test` only and cannot be selected by a production
configuration.

## Approved future smoke-test procedure

Once the external call has been explicitly authorized, the existing test-only fixture can run
the exact endpoint/persistence path once against a real model. It is guarded by both the
test-only property and an explicit environment approval token:

```bash
export CDG247_RUN_REAL_SMOKE=APPROVED
cd backend
bash ./mvnw test -Dtest=MaritimeAiSyntheticSmokeIntegrationTests \
  -Dcdg247.synthetic-smoke.use-real-client=true
```

The command reads the `MARITIME_AI_*` variables above, uses only
`SyntheticMaritimeAiSmokeFixture`, and sends one request. It must not be included in normal
CI or run against any non-test database.

The integration test itself performs the authenticated POST and GET, validates persistence,
and rolls its database transaction back afterward. Its report must show a `GENERATED` record
only when validation is `VALID`, including provider/model/prompt metadata, bundle hash,
citations, and uncertainties.

The browser panel is covered by the existing component/API tests. A live browser demonstration
would require a separately approved non-production runtime fixture because test-scope fixtures
are intentionally unavailable to a normal backend process. Do not promote this fixture into
production merely to render a demo.

## CDG-248/249 synthetic multi-source smoke test

`MaritimeAiSyntheticMultiSourceIntegrationTests` uses the same authenticated POST/GET,
eligibility, validator, persistence, and retrieval path. Its test-only context assembles—not
prebuilds—a bundle from separate fictional MPA-style schedule, port-notice, and regional
NEA-style rainfall adapters. The fixture includes a fourth, explicitly incompatible fictional
notice; the existing eligibility filter excludes that item before provider invocation.

The default remains a capturing, no-network stub. A real run requires **all three** gates:

```bash
export MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true
export CDG247_RUN_REAL_SMOKE=APPROVED
cd backend
bash ./mvnw test \
  '-Dtest=MaritimeAiSyntheticMultiSourceIntegrationTests#passesMpaNoticeAndNeaContentThroughTheProviderBoundaryThenPersistsAuthorizedAssessment' \
  -Dcdg247.synthetic-multisource.use-real-client=true \
  -Dcdg247.synthetic-multisource.capture-interpretation=true \
  -Dcdg247.synthetic-multisource.capture-path=target/cdg247-synthetic-multisource-interpretation-v5-typed-insights-real-response.json \
  -Dsurefire.rerunFailingTestsCount=0 \
  -DforkCount=1 \
  -DreuseForks=false
```

Use the same provider/model/endpoint/key configuration above. This command sends only the
fictional evidence IDs `synthetic-mpa-schedule-001`, `synthetic-notice-001`, and
`synthetic-nea-rainfall-001`; it never sends curated historical MPA/NEA records, recorded
replays, raw notices, or real shipment data. The explicitly incompatible
`synthetic-notice-irrelevant-001` is not sent.

A `GENERATED` / `VALID` result proves only that the structured response passed the existing
membership and safety validator. It does not independently prove maritime accuracy or natural-language
entailment. In the v5 contract, model-authored text is a typed planning insight linked to supplied
canonical fact references; the backend renders all factual schedule and evidence values separately. During a later
approved run, manually review every claim against the retained synthetic-only assessment:

1. Each evidence ID must be a supplied ID and each cited source must actually support its claim.
2. The model may use one or more relevant sources; it must not cite a source merely to maximise
   source count.
3. Distinguish the fictional schedule observation, notice period/area, and regional weather
   context; do not infer weather causation or vessel-specific conditions.
4. Reject invented cargo, real-shipment, delay, ETA, berth, or missed-connection consequences.
5. Confirm uncertainties and human-review requirements remain explicit and that no formal
   recommendation code is present.

The test verifies authorized GET retrieval before its transaction rolls the synthetic assessment
back. The default remains **no capture**. With the separate JVM property
`-Dcdg247.synthetic-multisource.capture-interpretation=true`, its test-only wrapper writes the
parsed interpretation, citation IDs, validation status, and provider/model metadata to
`backend/target/cdg247-synthetic-multisource-interpretation-postfix-real-response.json`, with POSIX owner-only (`0600`)
permissions. It records neither HTTP requests/responses, authorization headers, API keys, nor
evidence outside the fixed synthetic fixture. The same wrapper captures a parsed interpretation
when validation fails, before the transaction rolls back; raw provider responses are never
persisted. Do not log or retain API credentials or raw provider responses.

The original pre-role-grounding capture is immutable historical evidence at
`backend/target/cdg247-synthetic-multisource-interpretation-original-real-response.json`.
It used the earlier `cdg247.evidence.v1` / `maritime_ai_interpretation` contract. The rejected
post-fix capture reports `cdg247.evidence.v2.role-grounding` and schema name
`maritime_ai_interpretation_v2_role_grounding`. The deterministic-facts contract reports
`cdg247.evidence.v3.deterministic-facts` and schema name
`maritime_ai_interpretation_v3_deterministic_context`; do not overwrite or compare captures as if
they used the same response contract. The v5 typed-insight contract uses prompt version
`cdg247.evidence.v5.typed-planning-insights` and schema name
`maritime_ai_interpretation_v5_typed_planning_insights`; use the distinct v5 capture path above so
the historical captures remain unchanged.

Repeat with malformed output, a timeout, and omitted credentials. These must produce
   `FAILED` or `ABSTAINED`, never a fabricated generated assessment.

A successful HTTP response only verifies plumbing. It is not evidence of maritime accuracy,
causation, ETA quality, or a recommendation.
