# CDG-201 read-only arrival-evidence replay

This first DRIFT integration is deliberately fixture-backed. It does not ingest live MPA data from the standalone workspace and makes no database writes.

## Endpoint

`GET /api/shipments/{shipmentId}/cdg201-arrival-evidence`

The endpoint first uses the normal shipment-detail access check. It returns a JSON envelope with `readOnlyReplay: true`; `evidence` is either the recorded KOTA LARIS replay or `null`.

The replay appears only for a shipment whose stored mother-vessel IMO is `9351048`. Its association state remains `UNVERIFIED_IMO_ONLY`; that is not a booking, voyage, terminal, or actual-arrival match.

## UI demonstration

Run the normal backend/frontend locally, then open any authorized shipment with mother-vessel IMO `9351048`. The shipment detail displays **CDG-201 · Read-only replay** with:

- recorded source-string ETA change `2026-10-28 23:00:00` → `2026-10-29 02:00:00` (+180 minutes);
- collection-run IDs, UTC collection times, manifest and raw-file identifiers;
- raw MPA source strings plus derived Asia/Singapore and UTC event instants,
  while keeping the MPA-location mapping limitation explicit; and
- a separate synthetic scenario: 780 → 600 minutes, illustrative MEDIUM → HIGH.

The scenario is not the shipment risk, does not update planned arrival/departure fields, and does not trigger alerts.

## Local checks

```bash
cd backend && ./mvnw -Dtest=Cdg201ArrivalEvidenceServiceTest test
cd ../frontend && npm test -- --run src/shipment/api.test.ts src/shipment/Cdg201EvidencePanel.test.tsx src/shipment/ShipmentDetail.test.tsx
cd ../frontend && npm run build
cd ../frontend && npm run test:e2e:cdg201-replay
```

## Required approval before extending it

MPA source event strings are confirmed Asia/Singapore (UTC+08:00); collector retrieval timestamps remain UTC. CDG-42/team owners must still approve source precedence relative to AIS, MPA location-to-port mapping, visit identity, feeder/cargo-cutoff data, risk recalculation, alert behavior, and evidence retention before normalized instants affect operational logic. Until then, the read-only replay remains separate from `ArrivalEstimate`, `ConnectionWindowService`, `ShipmentRisk`, and alert activation.
