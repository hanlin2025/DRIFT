# CDG-256 validated assessment persistence and display

Normal application generation already persists a V9-valid assessment and exposes it through the
authenticated shipment assessment GET endpoint. CDG-257 intentionally rolls its integration-test
transaction back, so its successful real-provider capture does not leave a demonstration record.

For a no-additional-cost local demonstration, CDG-256 adds an opt-in test-only capture replay.
It reads the successful CDG-257 capture only when `CDG256_PERSIST_VERIFIED_CAPTURE=APPROVED` is
set, the file has POSIX `0600` permissions, the capture is `VALID` from `openai-responses`, and
its citations are exactly the approved MPA and NEA IDs. It then sends the parsed output through
the ordinary authenticated POST/GET assessment flow on `drift_test`. No OpenAI request is made.

The persisted provider label is `openai-responses (verified capture replay)`. The Shipment
Details UI explicitly explains that this is a verified capture replay rather than a new provider
call, while displaying its original model and prompt metadata. Failed or abstained records do not
render model interpretation as validated reasoning.

The replay is non-production only. It preserves the historical MPA/NEA limitations, does not
weaken V9 validation or authorization gates, and must not be used to present a replay as newly
generated inference.
