# CDG-251 authentic maritime AI assessment

## Scope and current evidence

CDG-251 builds on the CDG-247/248/249 stack. The application already has an authenticated,
read-only assessment API, canonical fact assembly, strict response validation, persisted assessment
metadata, and Shipment Details rendering. It does not modify shipment times, ETA estimation, risk,
alerts, recommendations, or human decisions.

The strongest currently curated authentic retrospective case is for IMO `9351048`:

- `mpa-expected-revision-9351048-20261026T154603`: MPA OCEANS-X expected-arrival revision from
  `2026-10-28T23:00:00+08:00` to `2026-10-29T02:00:00+08:00`, retrieved
  `2026-10-26T15:46:03.213897Z`.
- `nea-rainfall-20261027T153306`: NEA Singapore rainfall-station context observed
  `2026-10-27T23:25:00+08:00`, retrieved `2026-10-27T15:33:06.261657Z`.

Both records remain `RETROSPECTIVE_ANALYSIS` / `HISTORICAL_REPLAY`. An IMO establishes vessel
identity only; it does not establish a booking, cargo connection, port call, delay cause, or arrival
outcome. NEA remains regional Singapore context only. An authentic MPA notice is **not** in the
curated export. The CDG-242--244 notice pathway contains separate collection/retrieval tooling, but
no notice currently has an approved, visit- and geography-applicable curated record for this case;
no notice is included merely to create a three-source bundle.

## CDG-253: external AI processing eligibility

Access to a curated resource is not external-processing permission. No source-owner approval record
for external transmission is currently committed for either authentic item. The default configuration
therefore sends neither record to a provider.

An operator needs all of the following before any authentic provider request:

1. documented source-owner approval recording the source terms, permitted processor, attribution,
   retention/deletion condition, exact curated evidence ID, approver, and date;
2. `MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true` and normal provider configuration;
3. `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_INFERENCE_APPROVED=true`;
4. the required types in `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_SOURCE_TYPES_APPROVED`; and
5. only the separately approved immutable IDs in
   `MARITIME_AI_AUTHENTIC_RETROSPECTIVE_EVIDENCE_IDS_APPROVED`.

The final ID allowlist is intentional: approval of one MPA or NEA export does not authorise a later
export of the same type. These environment values are controls, not evidence that approval occurred.

## CDG-254: authentic evidence assembly

`CuratedMaritimeEvidenceAdapter` is opt-in and reads the packaged curated JSON resource only. It
requires matching mother-vessel IMO, an MPA revised expected arrival within 24 hours of the stored
mother planned arrival, collector retrieval at or before the assessment cutoff, and Singapore
transshipment context for NEA. Backfills never become live point-in-time data. The resulting bundle
preserves citations, source and retrieval times, provenance, source mode, geographic limitation, and
`UNVERIFIED_IMO_ONLY` association.

## CDG-255--257: inference, persistence, and validation status

Once the approvals above are recorded, the existing configured OpenAI Responses client can receive
only the bounded eligible bundle. Its strict schema and V9 canonical-reference validator still reject
unknown citations, unsupported causation, unsupported shipment-specific impact, invalid vessel roles,
and invalid relationship types before persistence. A successful result records provider/model/prompt
metadata and is exposed through the existing authorised GET endpoint and Shipment Details view.

No genuine provider assessment has been run for authentic evidence as of this document. That remains
blocked on CDG-253 approval; a technically valid response is not proof of source-use approval or of
factual correctness. Before a controlled run, review the generated claims and their evidence
entailment separately from schema/validator success.
