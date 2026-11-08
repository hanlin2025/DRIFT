# AIS capture exporter tests

The regular Python suite runs without a database. The real-`psql` regression is opt-in and requires a disposable PostgreSQL server exposed on a loopback port other than `5432`, a working `psql` client on `PATH`, and an explicit admin URL to that server's `postgres` maintenance database.

Set both `DRIFT_AIS_CAPTURE_TEST_DISPOSABLE=YES` and `DRIFT_AIS_CAPTURE_TEST_ADMIN_URL=postgresql://<test-user>:<test-password>@127.0.0.1:<disposable-port>/postgres` to enable it. The test rejects other hosts, the default PostgreSQL port, any admin database except `postgres`, and missing opt-in. It verifies the connected database and owner before creating a uniquely named `drift_ais_capture_test_<uuid>` database, verifies ownership again, inserts only fictional test records there, and drops that database during cleanup. It never reads or writes the retained `drift` or shared `drift_test` databases.

Run only against a disposable server you created for this test:

```sh
python3 -m unittest discover -s tools/ais_capture/tests -v
```

Without the opt-in variables, the integration test is reported as skipped. The unit tests still check the invocation mode, safe handling of invalid MMSIs, environment filtering, and backward-compatible CSV/manifest expectations.
