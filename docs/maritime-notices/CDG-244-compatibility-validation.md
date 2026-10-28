# CDG-244: Maritime-notice relevance and abstention validation

CDG-244 adds deterministic validation after CDG-243 discovery. Activity,
explicit named location, effective period, and supersession evidence each have
one of `COMPATIBLE`, `INCOMPATIBLE`, or `UNKNOWN` states. Unknown evidence is
never promoted to compatible.

An explicit incompatibility is rejected. A candidate with unknown evidence is
preserved for human review. Vessel-specific, ETA-impact, or causal questions
abstain because official notices cannot establish those conclusions.

`evaluate_notice_retrieval.py` accepts only caller-supplied local synthetic
cases and writes to a caller-selected ignored path. It ships no notice corpus,
PDF, source text, or evaluation result.
