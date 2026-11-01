# CDG-249 controlled maritime scenario replay

This replay is a **test-scope-only**, fully fictional scenario. It uses the same maritime context
assembly, compatibility filtering, canonical-fact construction, AI validation, persistence, and
authorised API controllers as the normal assessment workflow. It does not update operational ETA,
risk, alerts, probabilities, formal recommendations, or human decisions.

## Fictional scenario

The fictional mother vessel has a stored planned arrival of `2026-10-01 05:00 +08:00` and a
fictional feeder has a stored planned departure of `2026-10-01 14:00 +08:00`, yielding the
ordinary DRIFT planned connection window. The evidence record then documents a fictional expected
arrival revision to `06:00 +08:00`; it does **not** overwrite the stored planned arrival.

All three primary records are `SYNTHETIC_DEMONSTRATION`:

- `demo-synthetic-mpa-schedule-001`: fictional mother expected-arrival revision;
- `demo-synthetic-notice-001`: fictional, geographically compatible maintenance notice effective
  06:00--12:00 Singapore time;
- `demo-synthetic-nea-rainfall-001`: fictional regional weather context.

`demo-synthetic-notice-irrelevant-001` is intentionally incompatible and stays visible in the context
response for transparency, but is excluded before assessment input.

The MPA record's `05:00` and `06:00 +08:00` values are prior and revised **expected-arrival
values**, not publication or observation timestamps. The fictional replay only records its
retrieval at `2026-09-30 21:00Z`; source-event and observation time are deliberately displayed as
not recorded. The dated notice window is `2026-10-01 06:00--12:00 SGT`, so the backend can derive
the non-causal relationship `PORT_CONTEXT_OVERLAPS_REVISED_EXPECTED_ARRIVAL` using the revised
expected arrival; the stub emits `PORT_TIME_OVERLAP_REVIEW` for that relationship. Synthetic
association is fixture-defined only and does not verify cargo carriage or shipment impact.

## Deterministic stages and reset

The test fixture's clock is the only stage control. No database mutation injects a disruption.

| Stage | Assessment cutoff | Available evidence |
| --- | --- | --- |
| 0 — Baseline | 2026-09-30 20:45Z | none |
| 1 — Arrival revision | 2026-09-30 21:15Z | fictional MPA revision |
| 2 — Maritime context | 2026-09-30 22:45Z | MPA, compatible notice, NEA, plus the separately labelled incompatible notice |
| 3 — Grounded assessment | Stage 2 | normal authorised POST/GET assessment flow with an offline stub |
| 4 — Human review | Stage 3 output | existing Shipment Details panel labels evidence and AI advice separately |

The Spring integration test is transactional, so rollback is the reset mechanism: every execution
starts at Stage 0 and leaves no persistent demonstration shipment or assessment.

Run locally from `backend`:

```bash
bash ./mvnw test -Dtest=SyntheticMultiSourceMaritimeFixtureTest,MaritimeAiSyntheticMultiSourceIntegrationTests
```

This command makes no network or provider request. The test asserts generated/valid persisted
output via the authorised assessment endpoints, canonical references and citations, and rejection
of an unsupported causal claim. Existing Shipment Details tests exercise the UI's display of the
stored deterministic evidence summary and validated AI interpretation.

## Interactive local/staging demonstration

The application also has an explicit, disabled-by-default replay adapter. It is not a public
control endpoint: change a stage only by restarting a local/staging backend with the environment
below. It matches **only** a shipment reference beginning `DEMO-CONTROLLED-` with the exact
fictional vessel, IMO, port, and planned arrival described below.

```bash
export MARITIME_CONTEXT_DEMO_ENABLED=true
export MARITIME_CONTEXT_CONTROLLED_REPLAY_ENABLED=true
export MARITIME_CONTEXT_CONTROLLED_REPLAY_STAGE=BASELINE
export MARITIME_AI_ENABLED=true
export MARITIME_AI_CONTROLLED_REPLAY_STUB_ENABLED=true
cd backend && bash ./mvnw spring-boot:run
```

Retain the environment's existing required database and authentication configuration (including
`JWT_SECRET`); do not place those values in this document, a shell history, or source control. The
replay flags add no credentials and do not enable external inference.

Using an existing authorised user, create the fictional shipment through the normal Shipment
Details/Create Shipment flow (or the normal authenticated `POST /api/shipments` API):

```json
{
  "shipmentReference": "DEMO-CONTROLLED-W13",
  "origin": "Synthetic origin",
  "destination": "Synthetic destination",
  "transshipmentPort": "Synthetic Multi-source Port",
  "motherVessel": "Synthetic Multi-source Vessel",
  "motherVesselImo": "9000002",
  "plannedMotherArrivalAt": "2026-10-01T05:00:00+08:00",
  "feederVessel": "Synthetic Multi-source Feeder",
  "plannedFeederDepartureAt": "2026-10-01T14:00:00+08:00"
}
```

Open the shipment in the existing frontend. Restart the backend with
`MARITIME_CONTEXT_CONTROLLED_REPLAY_STAGE=ARRIVAL_REVISION`, then `MARITIME_CONTEXT_CONTROLLED_REPLAY_STAGE=MARITIME_CONTEXT`.
At the final stage, refresh the Shipment Details page and use the existing **Generate AI maritime
interpretation** control. The result is persisted through the ordinary authorised endpoint and is
explicitly labelled `controlled-replay-stub` / **Controlled simulation** in the panel. It survives
a browser refresh.

Reset safely by archiving only the displayed `DEMO-CONTROLLED-*` shipment through the existing
Admin/Logistics Manager archive control (or authenticated `PATCH /api/shipments/{id}/status` with
`{"status":"ARCHIVED"}`), then create a new uniquely suffixed demo shipment at `BASELINE`.
There is deliberately no delete/reset endpoint and no operation affecting non-demo records.

## Authentic retrospective counterpart

`AuthenticRetrospectiveMaritimeAiIntegrationTest` demonstrates the separately labelled
`RETROSPECTIVE_ANALYSIS` path using the retained MPA and NEA records. It retains
`UNVERIFIED_IMO_ONLY` and `REGIONAL_CONTEXT` limitations. It does not use this fictional scenario,
and it cannot claim a notice applies to KOTA LARIS without an independently supported visit/area
association.
