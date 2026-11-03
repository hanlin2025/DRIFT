# CDG-289 Phase 1: Assessment-history privacy

## Authorization rule

The shipment history endpoint first applies the existing shipment visibility rule using the authenticated account's current company and the current shipment assignment. It then treats each saved assessment as one indivisible record: interpretation, deterministic summary, importer deadline and impact facts, evidence, canonical references, outcome, and assessment metadata are either returned together or omitted together.

The managing company can read rows whose persisted `owning_company_id` matches the current shipment's managing company. An importer can read only rows with version 1 provenance naming that importer, and only while that company remains the shipment's current importer. The assessment service applies the same rule before converting records to response objects. There is no independent assessment-detail route today.

The history read locks the current shipment row while it selects and filters assessments. This keeps importer reassignment from passing between the current shipment authorization check and the history read.

## Provenance and legacy behavior

The existing `maritime_ai_assessments` schema identifies the managing company, but does not identify the importer whose private context was included at generation. Existing assessment JSON has no importer-company provenance either. Shipment change history records importer names, which cannot reliably identify a company. The service therefore does not infer ownership from the current assignment, text, or name-only history.

New rows store `historyScope.version` and the server-read importer company ID in the existing `output_json` envelope. This is written for successful and failed persisted assessments and is never copied to the API response. The existing `TEXT` column can hold the versioned envelope, so this focused fix needs no Flyway migration and does not consume a version currently being reconciled by CDG-252/#202 or CDG-203/#203.

Rows without recognized importer provenance, including all legacy rows, are hidden from importer history. The managing company retains access under the existing shipment authorization and matching `owning_company_id`. This intentionally narrows importer visibility for pre-fix assessments; the currently assigned importer does not inherit those historical rows.

## Compatibility and boundaries

Shipment authorization, AI generation, V15 grounding validation, prompt construction, assessment output, failure semantics, evidence gating, and persistence shape remain in place. Assessment response fields are unchanged. The only response behavior change is that an importer receives only assessments scoped to that importer; unauthorized shipment access keeps the existing 401/403 behavior.

No provider request is needed for the authorization regression. The PostgreSQL integration test uses the existing offline synthetic context and stub client, creates success and failure rows, models a legacy envelope without scope, and reassigns the shipment through the existing link API.
