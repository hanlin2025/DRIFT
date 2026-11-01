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

## One-request WSL procedure

After setting `MARITIME_AI_API_KEY` privately in the current shell, use the following command
from this branch. It creates a uniquely named empty local database from `template0`, selects one
JUnit method, disables Surefire reruns, and returns Maven's actual exit status. Never use the
shared `drift_test` database for this evaluation.

```bash
cd /tmp/cdg255-approved-authentic-inference
git pull --ff-only origin feature/CDG-257-verify-authentic-rag-grounding-failure-paths
cd backend

test -n "${MARITIME_AI_API_KEY:-}" || { echo "Set MARITIME_AI_API_KEY privately first."; exit 2; }
export MARITIME_AI_ENABLED=true
export MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true
export MARITIME_AI_AUTHENTIC_RETROSPECTIVE_INFERENCE_APPROVED=true
export MARITIME_AI_AUTHENTIC_RETROSPECTIVE_SOURCE_TYPES_APPROVED='MPA_ETA_REVISION,NEA_WEATHER'
export MARITIME_AI_AUTHENTIC_RETROSPECTIVE_EVIDENCE_IDS_APPROVED='mpa-expected-revision-9351048-20261026T154603,nea-rainfall-20261027T153306'
export MARITIME_AI_PROVIDER='openai-responses'
export MARITIME_AI_MODEL='gpt-4.1-mini'
export MARITIME_AI_ENDPOINT='https://api.openai.com/v1/responses'
export MARITIME_AI_TIMEOUT_SECONDS=20
export MARITIME_AI_MAX_OUTPUT_TOKENS=800
export MARITIME_CONTEXT_DEMO_ENABLED=true
export MARITIME_CONTEXT_CURATED_SNAPSHOTS_ENABLED=true
export CDG257_RUN_REAL_AUTHENTIC_SMOKE=APPROVED
export ISOLATED_TEST_DB="drift_cdg257_auth_$(date -u +%Y%m%dT%H%M%SZ)_$$"
docker exec drift-postgres psql -v ON_ERROR_STOP=1 -U drift -d postgres \
  -c "CREATE DATABASE ${ISOLATED_TEST_DB} OWNER drift TEMPLATE template0"
export DRIFT_TEST_DATABASE_URL="jdbc:postgresql://localhost:5432/${ISOLATED_TEST_DB}"
export DRIFT_TEST_DATABASE_USERNAME='drift'
export DRIFT_TEST_DATABASE_PASSWORD='drift'
umask 077
export CAPTURE="/tmp/cdg257-authentic-real-$(date -u +%Y%m%dT%H%M%SZ).json"
export LOG="/tmp/cdg257-authentic-real-$(date -u +%Y%m%dT%H%M%SZ).log"

bash -c '
  set -o pipefail
  ./mvnw test \
    "-Dtest=AuthenticMultiSourceRagIntegrationTest#sendsAuthenticMpaAndNeaFieldsToClientThenPersistsAndReturnsAuthorizedAssessment" \
    -Dcdg257.authentic-smoke.use-real-client=true \
    -Dcdg257.authentic-smoke.capture-interpretation=true \
    -Dcdg257.authentic-smoke.capture-path="$CAPTURE" \
    -Dsurefire.rerunFailingTestsCount=0 \
    -DforkCount=1 \
    -DreuseForks=false \
    |& tee "$LOG"
  status=${PIPESTATUS[0]}
  printf "\\nMaven exit code: %s\\nCapture: %s\\nLog: %s\\n" "$status" "$CAPTURE" "$LOG"
  test -f "$CAPTURE" && stat -c "capture permissions: %a %n" "$CAPTURE"
  exit "$status"
'
```

Review the local capture with `jq '{provider,model,promptVersion,validationStatus,citationIds,limitations,interpretation}' "$CAPTURE"`.
Never add the key, capture, or raw Maven log to Git.
