# CDG-247 evidence-grounded maritime interpretation

CDG-247 accepts only the authorized CDG-246 evidence bundle. It does not access the database, standalone collector directories, raw maritime documents, or credentials through its model-client contract.

Generation is disabled by default (`MARITIME_AI_ENABLED=false`). When enabled, the built-in client calls only an explicitly configured, approved structured-inference gateway (`MARITIME_AI_PROVIDER`, `MARITIME_AI_MODEL`, `MARITIME_AI_ENDPOINT`, and protected `MARITIME_AI_API_KEY`). It sends the bounded evidence package and requires an interpretation JSON response. Tests use deterministic fake client results; those are never labelled as live model output.

Each persisted `maritime_ai_assessments` row records the shipment, owning company, evidence-bundle version/hash, cutoff, provider/model/prompt metadata, state, validation result, and structured output or failure metadata. It is not a CDG-48 recommendation or a CDG-52 reviewer decision.

Validation rejects missing/unknown citations, unavailable or incompatible cited evidence, detectable causal assertions, malformed outputs, and formal recommendation codes. Evidence-ID validation cannot prove all natural-language claims are factually correct; human evaluation remains required.

Current CDG-246 inputs remain inadequate for a production RAG claim: MPA is recorded replay, the notice is synthetic, and NEA is unavailable. The smallest next integration is a persistence adapter that imports approved, provenance-backed MPA revision snapshots plus one official notice metadata/citation record and one point-in-time NEA observation/forecast record into DRIFT without copying raw PDFs.
