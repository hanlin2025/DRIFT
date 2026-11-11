# Scheduled MPA and NEA collection

The backend has an opt-in scheduled worker that invokes the repository's existing source collectors:

- `tools/cdg201/mpa/mpa_oceansx_collect.py` for MPA expected arrivals
- `tools/maritime_notices/mpa_official_notices_collect.py` for MPA public port notices
- `tools/cdg201/contextual/nea_weather_collect.py` for the NEA two-hour regional forecast

The worker runs each collector in its own temporary directory. It reads successful collector manifests and normalized outputs, maps source observations through `MaritimeSnapshotNormalizer`, and submits them to `MaritimeSnapshotChangeDetector` and the existing persisted snapshot store. Temporary raw responses are removed after each poll. Live NEA polling is limited to the two-hour forecast; recorded historical rainfall appears only in the explicitly labeled offline replay fixture. Collector errors and missing or invalid source pages are reported as unavailable; they do not delete or replace stored observations. The detector suppresses repeated content and keeps older observations from becoming the current head.

The saved OCEANS-X due-arrival schema exposes vessel IMO/call sign, route and expected arrival time, but no voyage or port-call identifier. The collector therefore keys an arrival by IMO, call sign (or vessel name fallback), route, and Singapore-local scheduled arrival date. This separates same-vessel/same-route calls on different dates and groups same-day ETA revisions; because the source supplies no durable call ID, a revision that crosses a date boundary is conservatively recorded under a new source identity.

## Runtime configuration

Keep collection disabled by default. Enable it only on one persistent backend worker with the repository tools and Python available:

```text
APP_MARITIMECHANGE_COLLECTION_ENABLED=true
APP_MARITIMECHANGE_COLLECTION_PROJECTDIRECTORY=/opt/drift-collectors
APP_MARITIMECHANGE_COLLECTION_PYTHON=python3
APP_MARITIMECHANGE_COLLECTION_INTERVALMS=1800000
APP_MARITIMECHANGE_COLLECTION_INITIALDELAYMS=30000
APP_MARITIMECHANGE_COLLECTION_SOURCETIMEOUTSECONDS=300
APP_MARITIMECHANGE_COLLECTION_RETRIES=1
APP_MARITIMECHANGE_COLLECTION_MPALOOKBACKDAYS=3
APP_MARITIMECHANGE_COLLECTION_NOTICELOOKBACKDAYS=14
APP_MARITIMECHANGE_COLLECTION_NEAAREAS=Tuas,Pioneer,Queenstown,Bukit Merah,Southern Islands
```

NEA two-hour forecasts are kept only for the configured forecast areas, which default to the areas containing Singapore's container and multipurpose terminals (a blank value uses the default). Each area is one source identity whose value is the forecast text, so the half-hourly reissue of an unchanged forecast is suppressed as a duplicate and only a change in an area's forecast becomes a `REVISED` event. The stored effective period is the valid period of the forecast that introduced the change. MPA notices released before the notice lookback (1–60 days, default 14) are skipped by the live poll: an older listing entry is not a newly observed change, and recording a whole year of notices on the first poll would attach them all to every Singapore shipment. A notice without a parseable release date is kept.

`project-directory` must contain the three scripts at the paths above and the worker's authorized `.env` when one is required. The backend Docker image installs Python 3 and copies only these three standard-library-only scripts to `/opt/drift-collectors`; it sets that as the collector project directory. MPA expected arrivals require the subscribed `MPA_OCEANSX_API_KEY`, `MPA_OCEANSX_API_BASE`, and `MPA_DUE_TO_ARRIVE_PATH` endpoint template consumed by the existing collector. The collector retries transient network, 429, and 5xx responses up to `MPA_REQUEST_RETRIES` (default 3) with exponential backoff, honors numeric `Retry-After`, and spaces requests by `MPA_REQUEST_PAUSE_SECONDS` (default 1 second); set these within the provider's subscription limits. NEA may use `DATA_GOV_SG_API_KEY` when the source requires it; its collector handles retries and backoff. Public MPA notice retrieval uses the existing unauthenticated collector. Put secrets in the hosting environment or an untracked secret file, never in source control or command-line arguments.

The scheduled tick submits work to a dedicated single-thread collector executor, leaving Spring's shared scheduler thread free for risk and mail jobs. A single-process overlap guard prevents queuing another poll while work is active. MPA expected arrivals use a three-day rolling lookahead and configurable 0–14 day lookback. The interval is constrained to 5 minutes through 24 hours (default 30 minutes); initial delay is constrained to 0 through 24 hours. Process timeouts are constrained to 30–300 seconds, wrapper retries to 0–2. Run one enabled replica; multiple replicas would duplicate source requests even though persisted duplicate events are suppressed. Set the interval and timeout to stay within provider access rules. A timed-out child process is terminated. Each source gets a bounded retry with exponential delay; the NEA collector retains its own per-request retries and rate-limit backoff. Failures log source, exception class, exit status, and up to 240 characters of sanitized collector diagnostics; credential-shaped values are redacted and source payloads are never logged. Mapping rejects are summarized by reason without record contents. MPA expected-arrival polling fails fast with an actionable diagnostic if `MPA_DUE_TO_ARRIVE_PATH` is not configured.

Collection health is single-instance in-memory state. It is reset when the backend restarts, so availability returns to `UNAVAILABLE` until the next live polling cycle completes. The collector and authenticated read endpoint must share that backend process for availability to describe that process's poll state; on another replica it describes only that replica's local collector state. Persisted events can still be read when collection is unavailable, and the API reports `PARTIAL` in that case. Replay does not mark any live source healthy. A live poll can retrieve an authentic record whose source event time is historical; that observation keeps `LIVE` source mode and `HISTORICAL` freshness, remains reviewable, and cannot advance a newer snapshot head or be represented as a forward-looking signal.

The read-only authenticated endpoint is:

```text
GET /api/shipments/{shipmentId}/maritime-change-events?since=<ISO-8601>&limit=<1..100>&cursor=<opaque>
```

It uses the existing shipment-view authorization. The first page is newest-first by detected/recorded time and stable row id; `nextCursor` advances toward older records. `since` is inclusive and filters detection/recording time, not the older source event time. Results include prior/current values, provenance, source mode, freshness, compatibility, and association confidence. Associated notices and weather are human-review cues only; this feature does not change operational shipment risk. Failed polling is reflected by response availability and leaves previous observations available. Compatibility fields and legacy source/observation time strings can be null or malformed, so clients should parse them defensively.

This feature adds no migration; it reads the existing V31 `maritime_snapshot_observations` table and indexes. Review query plans before collection volume grows; any paging index needs a separately coordinated migration version after the current open migrations and hosted history are reconciled.

## Offline deterministic replay

The compact fixture `backend/src/main/resources/maritime-context/cdg358-recorded-replay.json` contains selected fields from saved, authorized source observations and their retrieval manifests, not raw source dumps. It includes two MPA expected-arrival snapshots for ANGEL NO 1 (IMO 9470662, call sign VRGG5, SEAE→PEBGC, 2026-11-06 00:45→01:15 Singapore time), one official notice and two NEA rainfall readings (0.2 mm→0 mm). Replay labels them `RECORDED_REPLAY` with historical freshness. Rainfall is replay-only and is not polled live in this scope. The fixture is passed through the same normalizer and detector as scheduled observations; replaying it again produces no duplicate events.

Run the focused offline replay test without network access:

```bash
./mvnw -f backend/pom.xml -Dtest=MaritimeRecordedSnapshotReplayTest test
```

Run the full focused change-detection and scheduler unit set, without invoking live collection:

```bash
./mvnw -f backend/pom.xml -Dtest=MaritimeRecordedSnapshotReplayTest,MaritimeSnapshotNormalizerTest,MaritimeSnapshotChangeDetectorTest,MaritimeSnapshotShipmentMatcherTest,MaritimeSnapshotCollectionSchedulerTest,MaritimeSnapshotPollingMappingTest,MaritimeSnapshotPollingIdentityTest,MaritimeOfficialNoticePollingMappingTest,MaritimeChangeEventControllerIntegrationTests,RolePermissionMatrixTest test
```

The authenticated endpoint integration test runs only when `DRIFT_CDG358_JDBC_URL` points to a new, empty, owned disposable database matching `jdbc:postgresql://127.0.0.1:5432/drift_cdg358_<14-digit-timestamp>_<12-hex-token>`. Set `DRIFT_CDG358_DB_USERNAME`, `DRIFT_CDG358_DB_PASSWORD`, and `DRIFT_CDG358_OWNERSHIP_TOKEN` to the same credentials and database comment used by the project's disposable database guards. Without that explicit target, JUnit skips the database integration test; the URL, empty schema, Flyway target and ownership comment are checked before migrations run. Docker image verification is `scripts/ci/docker-builds.sh`.

To process the same fixture through the application detector and persisted store at backend startup, set `APP_MARITIMECHANGE_REPLAY_RUNONSTART=true`. Replay observations retain `RECORDED_REPLAY` mode and historical freshness and do not affect live collection health. The startup runner is opt-in; it does not poll sources.

Live collection needs the MPA subscription credentials and provider permission for the OCEANS-X endpoints. This implementation uses the source access logic and conservative request behavior in the existing collectors; fresh official records are not needed for the presentation replay. Replay is deterministic and network-free, but the live scheduler should be enabled only in a host that has the needed credentials, Python scripts and source authorization.
