# CDG-272 recommendation decision history

CDG-272 adds append-only history for accepted recommendation decisions. The
current `recommendation_decisions` row remains the operational current state;
the history table records accepted decisions and the actor name at decision
time.

## Migration allocation and order

The coordinated sequence on a fresh database is:

1. **V31** — main's maritime snapshot observations.
2. **V32** — CDG-252's AI validation-status widening (PR #202).
3. **V33** — CDG-272's recommendation decision history (this PR, #203).

Merge and deploy #202 before #203. With Flyway `outOfOrder=false`, a database
that reaches V33 before V32 exists will record V33 and will not later apply the
missing V32 as an ordinary upgrade. Do not ship #203 ahead of #202. The V33
migration backfills current decision rows once; it does not rewrite the current
decision table or reconstruct decisions already replaced there.

## Disposable migration and backfill checks

`RecommendationDecisionHistoryBackfillTest` requires
`DRIFT_CDG53_ADMIN_JDBC_URL`, `DRIFT_CDG53_DB_USERNAME`, and
`DRIFT_CDG53_DB_PASSWORD` for a disposable PostgreSQL server. It creates a
uniquely named `drift_cdg53_backfill_*` database, migrates it to V30, seeds
representative decisions, applies the remaining migrations with
`outOfOrder=false`, verifies the backfill and ordered migration history, then
drops only the database it created. The test rejects shared database targets.

The fresh-schema integration assertions require V31 before V33 and tolerate
V32 between them so the PR can be tested on its own branch and after #202 is
included. For the full migration chain, use a disposable database and migration
locations containing the V31, V32, and V33 scripts together; verify fresh
migration and a V30-to-latest upgrade with out-of-order execution disabled.
The backfill test accepts
`-Ddrift.cdg272.backfill.migration-location=filesystem:/path/to/combined/migrations`
to rehearse the same seed/backfill assertions with the combined migration set.

## Existing databases

Do not repair or edit retained Flyway history in place. The legacy
development/test database with the earlier CDG-272 migration recorded as V32
does not need preservation; recreate it only as an explicitly disposable
database when required. The separate local `drift` database that recorded the
old CDG-252 AI widening at V31 remains untouched because it conflicts with
main's V31 snapshot checksum and is not an upgrade target for this PR. Neither
history should be changed by `flyway repair` or manual edits as part of CDG-272.
