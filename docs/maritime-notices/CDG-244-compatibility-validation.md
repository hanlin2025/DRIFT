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

## Historical private evaluation (not reproduced by this branch)

The private standalone research evaluation reported validated top-1 retrieval of
13/14 and recall@5 of 13/14; one expired notice was correctly excluded by
compatibility filtering. It rejected 4/4 original and 4/4 held-out negatives,
abstained on 3/3 unsupported conclusions, and checked citations in 9/9 sampled
cases, with 16/16 standalone tests passing. These are historical results from a
small private corpus, not results newly reproduced by this Git branch. They do
not establish vessel impact, ETA causation, or operational applicability without
human review.
