# CDG-272 recommendation decision history

CDG-272 adds append-only history for accepted recommendation decisions. The
current `recommendation_decisions` row remains the operational current state;
the history table records accepted decisions and the actor name at decision
time.

## Migration allocation and order

The landed sequence, with Flyway out-of-order left disabled, is:

1. **V31** — maritime snapshot observations, merged from #210.
2. **V32** — maritime AI validation-status widening, merged from #202.
3. **V33** — recommendation decision history, added by this PR, #203.

V31 and V32 are already on main. This branch does not edit them. V33 is the
next version and runs only after V32. The V33 migration backfills current
decision rows once; it does not rewrite the current decision table or
reconstruct decisions already replaced there.

## Disposable migration and backfill checks

`RecommendationDecisionHistoryBackfillTest` requires
`DRIFT_CDG53_ADMIN_JDBC_URL`, `DRIFT_CDG53_DB_USERNAME`, and
`DRIFT_CDG53_DB_PASSWORD` for a disposable PostgreSQL server. It creates a
uniquely named `drift_cdg53_backfill_*` database, migrates it through V32,
seeds representative current decisions, applies V33 with `outOfOrder=false`,
verifies the backfill and that versions end at 31, 32, 33, then drops only
the database it created. The test rejects shared database targets.

## Existing databases

Do not repair or edit retained Flyway history in place. The legacy
development/test database with the earlier CDG-272 migration recorded as V32
does not need preservation; recreate it only as an explicitly disposable
database when required. The separate local `drift` database that recorded the
old CDG-252 AI widening at V31 remains untouched because it conflicts with
main's V31 snapshot checksum and is not an upgrade target for this PR. Neither
history should be changed by `flyway repair` or manual edits as part of CDG-272.
