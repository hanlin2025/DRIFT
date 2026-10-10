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
| Importer Organisation | No | Future importer-company association | Deferred | A blank value is valid. A nonblank value is not accepted until identifier, authorization, and linking rules are implemented. |

## Normalization and validation

- Required text values are trimmed. A blank value after trimming is invalid.
- Port and vessel values preserve their supplied casing. DRIFT currently has no authoritative port or vessel registry, so validation is limited to required-field and length checks.
- References preserve their supplied casing, but duplicate comparisons are case-insensitive. CDG-127 must detect duplicate references both within one CSV and against shipments for the applicable managing-company scope.
- Date-times must include an explicit offset. Planned Feeder Departure must be strictly later than Planned Mother Arrival.

## Organisation context

`shipments.company_id` is the primary/managing organisation. It can be either an importer managing its own shipment or a freight forwarder managing a shipment. `importer_company_id` is an optional associated importer organisation, but its null/self semantics for importer-managed shipments remain unresolved.

Importer Organisation is intentionally deferred. The current company display name is not safe as a CSV identifier because the model does not establish that names are unique. The database ID is stable but not a user-friendly CSV value, and the existing company code is not currently exposed as a confirmed organisation-import identifier. CDG-129 must define the identifier, forwarder-to-importer authorization, and separate view/edit permissions before nonblank values can be processed.

## Responsibilities by subtask

- **CDG-125:** defines this template and contract only.
- **CDG-127:** implements CSV parsing, header validation, normalization, row validation, and duplicate detection.
- **CDG-129:** implements importer identifier resolution, authorization, linking, and organisation visibility rules.

When CDG-127 is implemented, its backend header constants should be the canonical accepted-header set. A test should load `frontend/public/shipment-import-template.csv` and assert that its header row exactly matches that set, so the downloadable template cannot drift from the parser contract.
