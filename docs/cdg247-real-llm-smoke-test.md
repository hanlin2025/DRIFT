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

Repeat with malformed output, a timeout, and omitted credentials. These must produce
   `FAILED` or `ABSTAINED`, never a fabricated generated assessment.

A successful HTTP response only verifies plumbing. It is not evidence of maritime accuracy,
causation, ETA quality, or a recommendation.
