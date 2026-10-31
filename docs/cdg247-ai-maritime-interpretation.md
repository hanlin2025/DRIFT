# CDG-247 evidence-grounded maritime interpretation

CDG-247 accepts only the authorized CDG-246 evidence bundle. It does not access the database, standalone collector directories, raw maritime documents, or credentials through its model-client contract.

Generation is disabled by default (`MARITIME_AI_ENABLED=false`). When enabled, the built-in client calls only an explicitly configured provider (`MARITIME_AI_PROVIDER`, `MARITIME_AI_MODEL`, `MARITIME_AI_ENDPOINT`, and protected `MARITIME_AI_API_KEY`). `MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true` is additionally required before the configured client can transmit a provider request. `openai-responses` uses the OpenAI Responses API and strict JSON-schema output; other values retain the approved generic-gateway protocol, which accepts the bounded evidence package and returns an interpretation JSON response. Requests are bounded by `MARITIME_AI_TIMEOUT_SECONDS` (1–60, default 20) and `MARITIME_AI_MAX_OUTPUT_TOKENS` (64–4096, default 800). Invalid bounds fail safely before a provider request. Tests use deterministic fake client results; those are never labelled as live model output.

An authorized shipment viewer can explicitly request an assessment from the Shipment Details AI maritime interpretation panel. This POST is advisory and read-only: a disabled, unavailable, invalid, or insufficient-evidence result is shown as a safe abstention/failure and never changes the shipment, ETA, risk, alert, recommendation, or human decision.

Each persisted `maritime_ai_assessments` row records the shipment, owning company, evidence-bundle version/hash, cutoff, provider/model/prompt metadata, state, validation result, and structured output or failure metadata. It is not a CDG-48 recommendation or a CDG-52 reviewer decision.

Validation rejects missing/unknown citations, unavailable or incompatible cited evidence, detectable causal assertions, malformed outputs, and formal recommendation codes. Evidence-ID validation cannot prove all natural-language claims are factually correct; human evaluation remains required.

Current CDG-246 inputs remain inadequate for a production RAG claim: MPA is recorded replay, the notice is synthetic, and NEA is unavailable. The smallest next integration is a persistence adapter that imports approved, provenance-backed MPA revision snapshots plus one official notice metadata/citation record and one point-in-time NEA observation/forecast record into DRIFT without copying raw PDFs.
