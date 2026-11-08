# CDG-247 evidence-grounded interpretation evaluation plan

This plan evaluates advisory interpretation only. It does not assess ETA accuracy,
connection risk, recommendations, or operational decisions.

## Scenario set

Use independently authored, synthetic evidence bundles with expected outcomes recorded
before an LLM run. The initial set should include at least one scenario each for:

- a recorded ETA change whose meaning can be stated without claiming shipment impact;
- an unverified IMO-only vessel association, requiring an explicit uncertainty;
- a compatible notice with a citation;
- contradictory sources, requiring abstention or a clearly stated conflict;
- an incompatible or unavailable item, which must not support a claim;
- an irrelevant notice, which must be excluded from the interpretation;
- unsupported vessel, shipment, delay, or causal wording, requiring abstention;
- malformed, timed-out, or failed model output, requiring a persisted non-generated state.

## Comparison

For each frozen bundle, compare a deterministic evidence-only result (source IDs,
compatibility state, and fixed limitation text) with an LLM interpretation of the same
bounded package. Score each result independently for citation-ID validity, correct
abstention, evidence eligibility, acknowledgement of association uncertainty, and the
absence of causal claims or CDG-48/CDG-52 recommendation language.

Report exact counts by criterion. Do not use an LLM judge as the sole evaluator.
Two independent human reviewers should adjudicate natural-language support and
disagreements before any quality claim is made. A small synthetic set can expose safety
failures but cannot establish real-world operational usefulness or calibration.

## External smoke test gate

An approved smoke test requires a non-production inference gateway URL, a test-only
credential supplied through `MARITIME_AI_API_KEY`, `MARITIME_AI_PROVIDER`, and
`MARITIME_AI_MODEL`, plus `MARITIME_AI_ENABLED=true`. Send one synthetic bundle only;
do not send raw MPA PDFs, customer data, or live shipment evidence. Verify that the
gateway returns a JSON `MaritimeAiInterpretation`, that provider/model/prompt version
are persisted, and that a malformed response becomes `FAILED` or an unavailable client
result becomes `ABSTAINED`. A successful HTTP response is not a measure of model quality.
