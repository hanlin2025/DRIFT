# CDG-256 validated assessment persistence and display

Normal application generation already persists a V9-valid assessment and exposes it through the
authenticated shipment assessment GET endpoint. CDG-257 intentionally rolls its integration-test
transaction back, so its successful real-provider capture does not leave a demonstration record.

For a no-additional-cost local demonstration, CDG-256 adds an opt-in test-only capture replay.
It requires `cdg256.authentic-capture-replay-path` to name the capture and
`CDG256_PERSIST_VERIFIED_CAPTURE=APPROVED`. Before fixture writes, the committed test also
requires `CDG256_ISOLATED_DEMO_DATABASE` to start with `cdg256_demo_` and equal
`SELECT current_database()`. The normal shared `drift_test` database is refused. The capture
must have POSIX `0600` permissions, be `VALID` from `openai-responses`, and cite exactly the
approved MPA and NEA IDs. The parsed output goes through the ordinary authenticated POST/GET
assessment flow on that explicitly isolated demo database. No OpenAI request is made.

The persisted provider label is `openai-responses (verified capture replay)`. The Shipment
Details UI shows the replay notice only when the assessment is `GENERATED` and `VALID`, explaining
that this was a capture replay rather than a new provider call while displaying its original model
and prompt metadata. Failed or abstained records do not render model interpretation as validated
reasoning or show the verified-replay notice.

The replay is non-production only. It preserves the historical MPA/NEA limitations, does not
weaken V9 validation or authorization gates, and must not be used to present a replay as newly
generated inference.
