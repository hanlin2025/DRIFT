# CDG-257 guarded authentic OpenAI verification

This is a test-scope-only, single-request harness for the two project-approved curated
retrospective records:

- `mpa-expected-revision-9351048-20261026T154603` (`MPA_ETA_REVISION`)
- `nea-rainfall-20261027T153306` (`NEA_WEATHER`)

It is disabled unless both `cdg257.authentic-smoke.use-real-client=true` and
`CDG257_RUN_REAL_AUTHENTIC_SMOKE=APPROVED` are supplied. The normal production global
external-request gate remains mandatory, as do the authentic retrospective global approval,
exact type allowlist, and immutable evidence-ID allowlist. The test does not retry a failed
provider call.

The harness uses the existing curated MPA/NEA adapter, existing OpenAI Responses client,
`store:false`, and V9 typed canonical-grounding validator. It exercises authenticated POST and
GET persistence and verifies that planned schedule values and risk transitions do not change.

Before an endpoint assertion can fail, the existing test-only capture wrapper records the parsed
interpretation, V9 validation result, provider, model, prompt version, cited IDs, and retained
source limitations. It permits exactly the two IDs above and `RETROSPECTIVE_ANALYSIS` source
mode. It writes atomically with POSIX owner-only (`0600`) permissions and contains no API key,
authorization header, request, or raw provider response.

The evidence remains historical and bounded: the MPA item is an expected-arrival revision,
not an official MPA notice; `UNVERIFIED_IMO_ONLY` means no booking, cargo, visit, or outcome is
established; and NEA rainfall is regional context only, not vessel weather or delay causation.

Do not run this against a shared or production database. The manually operated command must use
an isolated local test database and a privately exported API key. A successful HTTP response or
JSON output is not success: `GENERATED` and `VALID` are required, and the capture must be
reviewed for unsupported claims before any downstream demonstration.
