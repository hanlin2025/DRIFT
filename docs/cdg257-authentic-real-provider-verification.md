# CDG-257 guarded authentic OpenAI verification

This is a test-scope-only, single-request harness for the two project-approved curated historical
records:

- `mpa-expected-revision-9351048-20261026T154603` (`MPA_ETA_REVISION`)
- `nea-rainfall-20261027T153306` (`NEA_WEATHER`)

The authentic MPA/NEA test is exactly
`AuthenticMultiSourceRagIntegrationTest#sendsAuthenticMpaAndNeaFieldsToClientThenPersistsAndReturnsAuthorizedAssessment`.
It is distinct from the synthetic-only smoke tests in `MaritimeAiSyntheticSmokeIntegrationTests`
and `MaritimeAiSyntheticMultiSourceIntegrationTests`, which use fictional `SYNTHETIC_DEMONSTRATION`
evidence. Do not substitute those tests or fixtures for this authentic-evidence evaluation. Run only
the selected JUnit method, never the whole integration-test class.

## Controls and payload

The test requires both `cdg257.authentic-smoke.use-real-client=true` and
`CDG257_RUN_REAL_AUTHENTIC_SMOKE=APPROVED`. The normal global external-request gate remains
mandatory, as do the authentic-retrospective gate and the exact source-type and immutable evidence-ID
allowlists. The selected method uses the existing curated MPA/NEA adapter and the configured OpenAI
Responses client. It invokes the client once; the client issues one POST and has no retry loop. Set
Surefire reruns to zero.

The request uses the current prompt version
`cdg247.evidence.v14.cdg252-abstract-revision-business-context.1`, bounded output and timeout, and
`store:false`. Its payload contains only the two allowed historical evidence records and minimized
synthetic schedule/risk context required by the typed contract. It contains no API credential,
customer identifiers, cargo details, or real importer-entered deadline. The evidence keeps its
original `RETROSPECTIVE_ANALYSIS` mode and `UNVERIFIED_IMO_ONLY` association limitation: the MPA item
is an expected-arrival revision, not an official notice or proof of a booking/outcome; NEA rainfall
is regional Singapore context, not vessel-specific weather or delay causation.

The test verifies that exactly those two evidence IDs reach the client, that the assessment is
persisted and returned through the authenticated API, and that schedule values and risk transitions
do not change. The test is transactional: rows are visible to its authenticated GET assertion but
are rolled back afterward, so this does not create a permanent production record.

When sanitized interpretation capture is enabled, the test wrapper writes only the parsed typed
interpretation, provider/model/prompt metadata, validator result, cited evidence IDs, and source
limitations. It does not write the request or raw OpenAI response body. The output file is created
atomically with owner-only (`0600`) permissions. Keep the capture and Maven log local; never commit
them or any API key.

## One-request WSL procedure

Use a clean worktree at the current `integration/cdg252-submission` head and a fresh local database.
The following creates an isolated worktree and database; never use the shared `drift_test` database.
Make the already-provisioned API key available as `MARITIME_AI_API_KEY` in the invoking shell without
echoing it or placing it in source, Git, logs, or the command line.

```bash
git worktree add --detach /tmp/drift-cdg252-real-provider origin/integration/cdg252-submission
cd /tmp/drift-cdg252-real-provider/backend

test -n "${MARITIME_AI_API_KEY:-}" || { echo "MARITIME_AI_API_KEY is not available in this shell."; exit 2; }
export MARITIME_AI_ENABLED=true
export MARITIME_AI_CONTROLLED_REPLAY_STUB_ENABLED=false
export MARITIME_AI_PROVIDER='openai-responses'
export MARITIME_AI_MODEL='gpt-4.1-mini'
export MARITIME_AI_ENDPOINT='https://api.openai.com/v1/responses'
export MARITIME_AI_TIMEOUT_SECONDS=20
export MARITIME_AI_MAX_OUTPUT_TOKENS=800
export MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true
export MARITIME_AI_AUTHENTIC_RETROSPECTIVE_INFERENCE_APPROVED=true
export MARITIME_AI_AUTHENTIC_RETROSPECTIVE_SOURCE_TYPES_APPROVED='MPA_ETA_REVISION,NEA_WEATHER'
export MARITIME_AI_AUTHENTIC_RETROSPECTIVE_EVIDENCE_IDS_APPROVED='mpa-expected-revision-9351048-20261026T154603,nea-rainfall-20261027T153306'
export MARITIME_CONTEXT_DEMO_ENABLED=true
export MARITIME_CONTEXT_CURATED_SNAPSHOTS_ENABLED=true
export CDG257_RUN_REAL_AUTHENTIC_SMOKE=APPROVED

ISOLATED_TEST_DB="drift_cdg257_auth_$(date -u +%Y%m%dT%H%M%SZ)_$$"
docker exec drift-postgres psql -v ON_ERROR_STOP=1 -U drift -d postgres \
  -c "CREATE DATABASE ${ISOLATED_TEST_DB} OWNER drift TEMPLATE template0"
export DRIFT_TEST_DATABASE_URL="jdbc:postgresql://localhost:5432/${ISOLATED_TEST_DB}"
export DRIFT_TEST_DATABASE_USERNAME='drift'
export DRIFT_TEST_DATABASE_PASSWORD='drift'

umask 077
CAPTURE="/tmp/cdg257-authentic-real-$(date -u +%Y%m%dT%H%M%SZ).json"
LOG="/tmp/cdg257-authentic-real-$(date -u +%Y%m%dT%H%M%SZ).log"
set +x
./mvnw test \
  "-Dtest=AuthenticMultiSourceRagIntegrationTest#sendsAuthenticMpaAndNeaFieldsToClientThenPersistsAndReturnsAuthorizedAssessment" \
  -Dcdg257.authentic-smoke.use-real-client=true \
  -Dcdg257.authentic-smoke.capture-interpretation=true \
  -Dcdg257.authentic-smoke.capture-path="$CAPTURE" \
  -Dsurefire.rerunFailingTestsCount=0 \
  -DforkCount=1 \
  -DreuseForks=false > "$LOG" 2>&1
MAVEN_STATUS=$?
printf 'Maven exit code: %s\n' "$MAVEN_STATUS"
stat -c 'capture permissions: %a' "$CAPTURE"
exit "$MAVEN_STATUS"
```

Do not rerun if the request fails, times out, or is rejected by the validator. Review the local
capture with `jq '{provider,model,promptVersion,validationStatus,citationIds,limitations,interpretation}'
"$CAPTURE"`. Preserve the original evidence limitations and assess every claim against the actual
MPA/NEA fields and supplied planning/risk facts.

## Evaluation record — 2026-11-02

- Tested commit: `381ed0d33e0b0e64bdfa78a2e5e85bf823485dc5`.
- Provider/model/prompt: `openai-responses` / `gpt-4.1-mini` /
  `cdg247.evidence.v14.cdg252-abstract-revision-business-context.1`.
- Paid OpenAI calls: one. The Responses request completed and the structured response parsed into
  the typed interpretation. No retry was made.
- Evidence sent: exactly `mpa-expected-revision-9351048-20261026T154603` and
  `nea-rainfall-20261027T153306`, both retained as `RETROSPECTIVE_ANALYSIS` and
  `UNVERIFIED_IMO_ONLY`. The capture wrapper's exact-ID and retrospective-mode guard passed before
  delegating to OpenAI; the later in-test evidence assertions were not reached after the `FAILED`
  response. Project-scope approval was confirmed for these records; external-request, authentic-
  inference, source-type, and exact-ID technical gates were enabled only for this invocation. The
  request used `store:false`.
- Validator: `UNSUPPORTED_CARGO_ASSERTION`; assessment state: `FAILED`. The MPA and NEA citations
  present in the output were from the approved set, but the `RISK_BUFFER_REVIEW` claim contained no
  evidence citation, so citation grounding for the complete interpretation did not pass.
- The POST returned the failed assessment. The test stopped at its assertion that state must be
  `GENERATED`, before the authenticated GET assertion; persistence/retrieval acceptance therefore
  did not pass. The test is transactional and rolled back; the isolated database had no persisted
  assessment after the run.
- The V14 human-follow-up instruction is present in the prompt and asserted by the offline client
  test. The response includes a carrier/operations-team verification action, so the prior
  `MISSING_HUMAN_RISK_FOLLOW_UP` rejection did not recur. A human-owner factual review has not been
  performed. This content assessment is not acceptable for a human review sign-off: the MPA
  schedule-review wording is appropriately tentative and the uncertainty preserves the unverified
  IMO-only association. The NEA statement stays regional
  and does not claim causation. However, an uncertainty mentions cargo handling/connection despite
  the absence of cargo facts. The risk claim says there is no remaining connection window even
  though the selected planned window is available; it reports estimate freshness as `UNKNOWN` even
  though the backend input is `UNAVAILABLE`; and it has no MPA evidence reference. A follow-up also
  asks to evaluate alternative shipment plans, which goes beyond verification and into operational
  planning. The output does not claim an actual delay, loss, missed connection, probability, or
  causal link. It does provide a human verification action naming the carrier or operations team.
- The sanitized capture is retained locally at
  `/tmp/cdg257-authentic-real-20261102-58124.json` with mode `0600`; the Maven log is local only and
  must not be committed. No API key or raw provider response body is included in the capture.

The exact first validator rejection is caused by cargo wording in the authored uncertainty text;
`MaritimeAiResponseValidator` rejects cargo assertions across summaries, claims, uncertainties, and
follow-ups. The captured output also contains risk-reference/window/freshness mismatches that the
validator did not reach because validation stops at the first failure. Do not weaken the validator.
The smallest legitimate follow-up is to strengthen a newly versioned prompt to prohibit cargo
references even when framed as uncertainty, require each risk-review claim to cite its MPA evidence
reference, copy the backend risk state exactly, and limit follow-ups to human verification rather than
plan evaluation. Add offline regressions for these cases before any new paid evaluation. A second
provider call requires fresh authorization.
