# CDG-201 timestamp semantics

MPA and NEA source date/time values are confirmed Singapore Standard Time
(`Asia/Singapore`, UTC+08:00) for this project.

- MPA naive expected-arrival, reported-arrival, and reported-departure strings
  remain immutable raw evidence. Derived SGT and UTC representations are added
  alongside them; they do not overwrite source strings.
- MPA collector retrieval/request timestamps remain UTC evidence timestamps.
- NEA timestamps that include an offset remain their original instant. Convert
  to SGT only for display or Singapore-date grouping; never attach `+08:00`
  twice.
- Historical NEA backfills stay retrospective and are not leakage-safe features
  merely because their source observation time precedes an MPA cutoff.

Before CDG-42 can use normalized instants operationally, its owner must approve
source precedence relative to AIS, visit identity, MPA location-to-port mapping,
shipment-leg association, transfer-buffer inputs, and risk/alert behavior.
