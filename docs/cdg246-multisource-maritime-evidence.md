# CDG-246 multi-source maritime evidence bundle

`GET /api/shipments/{shipmentId}/maritime-context` is a read-only, shipment-authorized evidence bundle. It retains the CDG-245 feature flag and uses `Cache-Control: no-store`.

## Current adapters

- `MPA_ETA_REVISION` — a recorded CDG-201 KOTA LARIS replay, including prior and revised raw values, a measured `+180 minutes` change, manifest/raw-response identifiers, and UTC retrieval time.
- `MPA_OFFICIAL_NOTICE` — an explicitly synthetic demonstration item. It contains no official notice text and no asserted route, terminal, berth, or disruption relationship.
- `NEA_WEATHER` — an explicit `UNAVAILABLE` item. DRIFT does not read the standalone weather collection directory and does not infer weather values or causation.

Each item independently exposes `sourceMode` (`LIVE`, `RECORDED_REPLAY`, `SYNTHETIC_DEMONSTRATION`, or `UNAVAILABLE`), provenance, association confidence, compatibility states, limitation, and human-review requirement. `aiExplanation` and `suggestedActions` remain `null` reserved seams for CDG-247.

## Point-in-time rule

The bundle is assessed at a UTC cutoff. Recorded items with a retrieval timestamp after that cutoff are omitted; their source-event timestamp cannot make them available earlier. This prevents future observed changes from becoming an earlier feature. Source-event strings remain source representations; CDG-201's MPA event convention is SGT and its collection timestamps remain UTC.

Example enabled replay response (abridged):

```json
{
  "contractVersion": "cdg246.maritime-evidence-bundle.v1",
  "sourceMode": "RECORDED_REPLAY",
  "assessmentCutoffUtc": "2026-10-28T00:00:00Z",
  "shipmentAssociationConfidence": "UNVERIFIED_IMO_ONLY",
  "evidence": [{
    "sourceType": "MPA_ETA_REVISION",
    "sourceMode": "RECORDED_REPLAY",
    "previousValue": "2026-10-28 23:00:00",
    "currentValue": "2026-10-29 02:00:00",
    "changeMagnitude": "+180 minutes"
  }]
}
```

No item updates a shipment, ArrivalEstimate, connection window, risk, alert, or operational schedule. A matching IMO is only `UNVERIFIED_IMO_ONLY` association.

## Next steps

CDG-246 needs approved persistent ingestion of validated CDG-201, CDG-242--244, and NEA snapshot records before a `LIVE` adapter can be enabled. CDG-247 can consume this contract to produce separately persisted, evidence-cited explanations only after its own review and abstention rules are implemented.
