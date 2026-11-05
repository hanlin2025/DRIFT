# CDG-290 Offline Controlled Scenario Demo

This procedure runs the existing React UI, Spring Boot API and CDG-288 scenario service against a new disposable PostgreSQL 16.15 container. The database data directory is tmpfs, the container is labelled for this demo, and cleanup removes only that container and its private runtime file. The repository's shared Compose database, `drift_test`, named volumes and collected AIS observations are not used.

The procedure keeps AISStream and OpenAI disabled. Scenario assessments use the explicitly labelled `controlled-replay-stub` built into this local demo configuration. The UI must display **“Controlled synthetic stub assessment. It uses fictional evidence and is not an external provider response.”** This output is not an OpenAI response.

## Prerequisites

- Docker Engine with `postgres:16.15` available (the first run may need to pull this image).
- Java/Maven wrapper and Node/npm dependencies already available for this checkout.
- Ports 18081 and 15174 free on loopback.
- Run commands from the repository root.

Package downloads for Maven/npm may require internet access on a cold machine. Runtime map assets, scenario data, and AI interpretation stay local; the run makes no AISStream or OpenAI requests.

## Start the disposable database and backend

In terminal 1:

```bash
set -euo pipefail
DEMO_START_OUTPUT="$(./scripts/cdg290-demo/start-postgres.sh)"
printf '%s\n' "$DEMO_START_OUTPUT"
DEMO_ENV="$(printf '%s\n' "$DEMO_START_OUTPUT" | sed -n 's/^Runtime configuration: //p')"
printf 'Demo environment: %s\n' "$DEMO_ENV"
./scripts/cdg290-demo/run-backend.sh "$DEMO_ENV"
```

The startup command prints no generated database password or JWT secret. Wait for Spring Boot to report that it started and Flyway validated/applied migrations through V32. The backend binds only to `127.0.0.1:18081`, uses a new database in a labelled container's tmpfs, has controlled scenario and its offline stub explicitly enabled, and leaves external AI approval, AIS, and alert dispatch disabled.

## Create the demo tenant and anchor shipments

In terminal 2, use the runtime path printed above:

```bash
./scripts/cdg290-demo/seed-demo-tenant.sh /tmp/drift-cdg290-offline-<run-id>/demo.env
```

Enter a local-only password when prompted. It is not stored in the runtime file or printed. The script creates one invitation and registers the presenter through the real backend API, then creates two ordinary anchor shipments through `POST /api/shipments`. It verifies the generated user and shipment IDs before reporting success. Fixed IDs 29000–29002 are used only in this fresh database so the backend can restrict scenario access to that presenter and the A/B bindings at startup.

The ordinary shipment creation hooks record two `CREATED` history events and two initial risk transitions for these demo anchors. In the verified rehearsal both shipment versions remained `0`, with no shipment field-change events, risk alert deliveries, vessel observations, or persisted AI assessments. Scenario stage commands and transient stub assessments did not change shipment records. All of these fixture rows live only in this disposable database and disappear during cleanup.

Expected output includes:

```text
Demo tenant and two ordinary anchor shipments created in disposable database cdg290_demo.
Presenter login: cdg290.offline@example.test (password entered privately; never written to disk).
Shipment A ID: 29001; Shipment B ID: 29002.
```

If registration or either shipment creation fails, clean up this run and start again with a fresh generated `demo.env`; do not reuse an existing development database.

## Start the frontend

In terminal 3:

```bash
./scripts/cdg290-demo/run-frontend.sh /tmp/drift-cdg290-offline-<run-id>/demo.env
```

Open `http://127.0.0.1:15174/login` and sign in with the presenter email and the password entered during setup. Open shipment A (ID 29001) in Shipment Details. The local frontend explicitly enables the scenario entry and scenario AI panels; the backend still requires the selected viewer/company and A/B shipment IDs to match its startup allowlist.

For the scenario map, Vite serves `/cdg290-offline-style.json` and `/cdg290-offline-grid.geojson` from this checkout. The style has no remote tile, glyph or sprite source. It is a schematic coordinate grid, not a navigational chart and has no coastline data. It shows only the synthetic scenario markers and does not download OpenFreeMap tiles.

## Presenter flow and expected backend results

1. From shipment A, select **Enter controlled scenario**, then **Start**. The service returns `BASELINE`; the initial snapshot has low risk, the ordinary planned schedule remains visible in the scenario facts, and no AIS/provider request runs. If AI is requested at baseline, the expected result is an explicit insufficient-evidence state with no interpretation.
2. Select **Next** once. The returned snapshot is authoritative for `CHANGE`: the backend reports medium scenario risk and an estimated arrival of **2026-10-01 16:00 UTC**. Shipment A's **2026-10-01 12:00 UTC** transshipment-arrival deadline is exceeded by 4 hours; Shipment B's **2026-10-02 10:00 UTC** deadline is satisfied. These are backend-calculated scenario facts.
3. Ask for the scenario AI interpretation while viewing CHANGE. It is a transient, validated local stub result tied to the current shipment, session, epoch, snapshot and revision. Show the explicit stub notice. No provider request or stored assessment is involved.
4. Select **Next** once more for `WORSENED`. The backend reports high scenario risk and an estimated arrival of **2026-10-02 08:00 UTC**. Shipment A exceeds its deadline by 20 hours; Shipment B remains within its deadline by 2 hours. Generate/show the explicitly labelled stub interpretation, and switch A/B to confirm both the displayed scenario facts and interpretation binding change with the selected authorized anchor.
5. Select **Reset**. Confirm a new epoch and the `UNSTARTED` state with no snapshot, scenario positions or interpretation left visible. Exit controlled viewing (the existing **Live** action exits the scenario), and confirm ordinary Shipment Details returns for the selected real anchor shipment.

At WORSENED, the current synthetic AI evidence contains notice and weather context, but not the earlier mother-vessel schedule revision. The offline stub therefore makes context-only claims at this stage; it does not assert A/B deadline causality. The scenario snapshot itself continues to show backend-calculated risk, ETA and each shipment's deadline comparison.

Only the scenario API provides stage transitions, scenario risk, ETA, deadline comparison and A/B consequence values. Do not describe them as frontend estimates or persisted shipment changes. The controlled view does not request shipment tracking/history or call ordinary maritime AI; after exiting, ordinary Shipment Details resumes its normal authorized reads. AIS remains disabled, so those reads cannot trigger a provider feed. No CSV import, OpenAI endpoint or external provider is needed for the scenario flow.

## Cleanup

Stop the frontend and backend with Ctrl+C. Then run:

```bash
./scripts/cdg290-demo/cleanup.sh /tmp/drift-cdg290-offline-<run-id>/demo.env
```

The script checks the generated run ID and Docker labels, stops only this `--rm` container, and removes its private runtime directory. PostgreSQL data was on tmpfs and disappears with the container. No shared Compose container, volume, test database, or captured AIS dataset is stopped or deleted.

## Troubleshooting

- **Docker unavailable or the image cannot be pulled:** start Docker or make `postgres:16.15` available, then rerun `start-postgres.sh`.
- **Backend cannot bind port 18081 or frontend cannot bind 15174:** stop the conflicting local process. Keep the backend/frontend ports consistent with the generated runtime file and both Vite proxy and backend startup settings.
- **Migrations do not finish:** wait for Flyway to complete through V32. If migration validation fails on this newly created database, stop and report the failure; never run `repair` or alter historical migrations.
- **Seeder returns an HTTP error:** verify the backend reports started, use the `demo.env` from the same run, and check the HTTP status shown. If any registration step partially completed, discard the run and start over.
- **Scenario access is denied:** check the generated runtime file is passed to both startup scripts and that the presenter logged into the newly seeded account. Do not broaden the allowed user/company IDs.
- **Stub is unavailable:** confirm `MARITIME_AI_ENABLED`, `MARITIME_AI_SCENARIO_ENABLED` and `MARITIME_AI_CONTROLLED_REPLAY_STUB_ENABLED` are true in the backend startup environment. Confirm provider endpoint/key are empty and external request approval remains false.
- **No map appears:** confirm the browser can load `/cdg290-offline-style.json` and `/cdg290-offline-grid.geojson` from the local frontend. A missing local asset must not be worked around by enabling external tiles for this demo.
- **AI displays an error or abstains:** baseline intentionally has no evidence. CHANGE/WORSENED need their current synthetic evidence and snapshot. Refresh the scenario state after a stage or A/B change; do not retain an older interpretation.
