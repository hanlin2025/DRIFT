# CDG-201 read-only replay: end-to-end validation

Validated locally on 27 October 2026 using the normal Spring Boot/Vite stack
and a disposable, test-owned PostgreSQL database. The test runner created and
removed only a database whose name matches its `drift_cdg...` ownership guard.
It did not modify the shared development data or scheduled collectors.

## Result

The recorded KOTA LARIS replay is demo-ready as a read-only evidence feature.
The test created an owned shipment with mother-vessel IMO `9351048` and
verified that the endpoint and shipment-detail panel show:

- recorded MPA expected-arrival revision: `2026-10-28 23:00:00` to
  `2026-10-29 02:00:00` (`+180` minutes);
- collection timestamps, run IDs, manifests and raw-file references;
- `UNVERIFIED_IMO_ONLY` shipment association;
- confirmed SGT source-time semantics, derived UTC representations, and
  display-only MPA location-reference metadata;
- an explicitly simulated connection scenario of `780` to `600` minutes.

The UI labels the scenario as simulated and the evidence as a read-only replay;
it does not claim a booked shipment, real feeder connection, or missed
connection.

## Isolation and immutability checks

The browser/API test verified all of the following:

- an unauthenticated evidence request is rejected (`401`);
- a different-company user cannot receive replay evidence (`401` or `403`,
  depending on the application's existing visibility layer);
- unknown shipment IDs produce a non-disclosing denial (`401` or `404`);
- a shipment with another mother-vessel IMO receives a successful response with
  `evidence: null`;
- reading the replay leaves shipment planned timestamps, connection-window
  values, risk state, and entity version unchanged.

The current application returns `401` for some non-visible/unknown shipment
lookups rather than `404`. This is non-disclosing and is existing visibility
behaviour, not a CDG-201 risk or data leak.

## Commands executed

```bash
cd backend
./mvnw -q -Dtest=Cdg201ArrivalEvidenceServiceTest test

cd ../frontend
npm run build
npx vitest run src/shipment/api.test.ts src/shipment/Cdg201EvidencePanel.test.tsx src/shipment/ShipmentDetail.test.tsx --reporter=dot
npm run test:e2e:cdg201-replay
```

Results: backend service tests passed (2); frontend build passed; focused
frontend suite passed (123); disposable database Playwright end-to-end test
passed (1).

## Demonstration

1. Start the normal local application stack and sign in with an account allowed
   to view a test shipment.
2. Open a shipment whose `motherVesselImo` is `9351048`.
3. The shipment detail displays **CDG-201 · READ-ONLY REPLAY** with KOTA LARIS,
   the `+180 minutes` observed revision, raw-source provenance and limitations.
4. Review the separately headed **SIMULATED CONNECTION SCENARIO**: `780 → 600`
   minutes. Explain that feeder timing, buffer, and risk wording are simulated,
   while only the MPA timestamp revision is recorded evidence.

## Remaining limits

- The endpoint is fixture-backed replay, not live MPA ingestion.
- Mother-vessel IMO is only an `UNVERIFIED_IMO_ONLY` association; it is not a
  verified shipment or vessel-visit link.
- MPA source strings are retained verbatim and interpreted as
  Asia/Singapore (`UTC+08:00`) in separate derived fields; collection times
  remain UTC.
- `PEBGA` is shown only as an exact code-string reference match to `PILOT EAST
  BOARD GRD A` with a derived `PILOT_GROUND` category. It is not mapped to a
  DRIFT terminal; `SEA` remains explicitly unknown.
- The full official MPA location reference is not redistributed in this
  repository pending reuse approval.
- No shipment, ArrivalEstimate, CDG-42 risk result, alert or audit/business
  event is mutated by this feature.
