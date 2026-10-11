# Shipment import CSV contract

## CSV columns

| Column | Required | Maps to | Format | Validation |
| --- | --- | --- | --- | --- |
| Tracking/BL No. | Yes | `shipmentReference` | Text | Trim whitespace; 1-100 characters. Preserve displayed casing. Duplicate comparison is case-insensitive. |
| Origin Port | Yes | `origin` | Text | Trim whitespace; 1-200 characters. |
| Destination Port | Yes | `destination` | Text | Trim whitespace; 1-200 characters. |
| Transshipment Port | Yes | `transshipmentPort` | Text | Trim whitespace; 1-200 characters. |
| Mother Vessel | Yes | `motherVessel` | Text | Trim whitespace; 1-200 characters. |
| Planned Mother Arrival | Yes | `plannedMotherArrivalAt` | ISO-8601 offset date-time | An explicit timezone offset is required, for example `2026-10-10T09:30:00+08:00`. |
| Feeder Vessel | Yes | `feederVessel` | Text | Trim whitespace; 1-200 characters. |
| Planned Feeder Departure | Yes | `plannedFeederDepartureAt` | ISO-8601 offset date-time | An explicit timezone offset is required and the value must be strictly after Planned Mother Arrival. |
| Importer Organisation | No | Future importer-company association | Company Code | A blank value is valid. A nonblank value is an exact stored Company Code, retained for later authorization and linking. |

## Normalization and validation

- Required text values are trimmed. A blank value after trimming is invalid.
- Port and vessel values preserve their supplied casing. DRIFT currently has no authoritative port or vessel registry, so validation is limited to required-field and length checks.
- References preserve their supplied casing, but duplicate comparisons are case-insensitive. CDG-127 must detect duplicate references both within one CSV and against shipments for the applicable managing-company scope.
- Date-times must include an explicit offset. Planned Feeder Departure must be strictly later than Planned Mother Arrival.

## Organisation context

`shipments.company_id` is the primary/managing organisation. It can be either an importer managing its own shipment or a freight forwarder managing a shipment. For an importer-managed shipment, `importer_company_id` remains null. For a forwarder-managed shipment, it may reference a distinct linked importer.

Importer Organisation is optional. A nonblank value is resolved only as an exact stored Company Code; company display names are never used. The parser preserves normalized nonblank text without resolving it to a company or granting access. When an import is later orchestrated, the company must be active and the importing freight forwarder must have an active forwarder-to-importer authorization relationship before the value can be linked to a shipment.

ADMIN bulk-import authorization remains deferred: the current Company model has no reliable company-type classification, so an ADMIN account cannot safely be identified as belonging to a freight-forwarder company. A future company-type/domain classification must be introduced before that rule can be completed.

## Responsibilities by subtask

- **CDG-125:** defines this template and contract only.
- **CDG-127:** implements CSV parsing, header validation, normalization, row validation, and duplicate detection.
- **CDG-129:** implements importer identifier resolution, authorization, linking, and organisation visibility rules.

When CDG-127 is implemented, its backend header constants should be the canonical accepted-header set. A test should load `frontend/public/shipment-import-template.csv` and assert that its header row exactly matches that set, so the downloadable template cannot drift from the parser contract.
