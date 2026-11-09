# CDG-290 controlled-scenario AI v1.6

The v1.6 Responses contract is scoped to the controlled synthetic scenario. It asks the model to write two short, evidence-grounded review statements, one uncertainty, and two human verification actions. The backend remains authoritative for risk severity, connection-window state, estimate freshness, deadline applicability, comparison outcome, evidence eligibility, and canonical references.

The model returns typed risk and deadline values alongside its authored prose. The backend compares every typed value with the scenario evidence package, resolves each evidence reference to one eligible synthetic mother expected-arrival revision, and adds the required risk and business/comparison references when it builds the existing interpretation shape. The provider schema fixes the object shape and enums; the backend parser also enforces response and field length limits. It does not derive usefulness from deterministic display text: the accepted risk statement, deadline statement, uncertainty, and follow-ups come from the model output.

## Controlled English grammar

Natural-language acceptance uses the explicit whole-sentence alternatives in `ScenarioMaritimeAiProse`. The same alternatives produce the prompt instructions and drive validation, so a prompted choice has a corresponding accepted form. They cover the scenario's revision relevance, backend risk facts and window state, the two deadline outcomes, uncertainty about actual arrival, and the two verification checks. Word spacing, case, and common hyphen variants are normalized; an added clause, unsupported fact, or contradiction makes the sentence fail.

This is a deliberately bounded grammar for the CDG-290 demo, not a general semantic-equivalence system. It does not claim to understand arbitrary paraphrases. The tests exercise every listed alternative and representative natural paraphrases assembled from those choices. Broader semantic coverage requires a separately reviewed contract and evaluation set; it must not be inferred from these controlled-scenario results.

## Provider and acceptance boundary

The OpenAI Responses scenario request uses `cdg290.scenario-grounded-v1.6`, strict JSON Schema, `store:false`, and a minimized data projection. The existing generic V18 path and persisted V34 representation remain in place. BASELINE abstains before the client call. Positive stages abstain before the call if required current backend facts or an eligible revision are missing.

Offline fixtures prove contract behavior and are not evidence that a genuine model response satisfies the grammar. A provider acceptance run requires separate explicit authorization and review of the exact authored output.
