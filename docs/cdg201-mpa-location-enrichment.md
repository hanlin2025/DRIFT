# CDG-201 MPA location-description enrichment

The CDG-201 replay preserves raw MPA location strings as source evidence. It
may attach an official description only for an exact code-string match from the
reviewed MPA Location Codes reference. This is display metadata, not an
operational mapping.

## Replay scope

The KOTA LARIS replay contains two actual source fields from the MPA OCEANS-X
Due to Arrive response:

- `locationFrom=SEA`: no official description is displayed because no exact
  reviewed reference match exists.
- `locationTo=PEBGA`: displays `PILOT EAST BOARD GRD A` and derived category
  `PILOT_GROUND`, both marked `PARTIAL`.

The full official reference export is intentionally not committed because its
redistribution terms have not been approved. The backend therefore contains a
small, event-specific lookup rather than a bundled general-purpose reference
dataset.

## Safety boundary

An exact code-string match does not establish that an OCEANS-X field shares
the official reference namespace, identify a DRIFT port or terminal, prove a
vessel visit, or establish physical berthing/cargo handling. Unknown,
conflicting, or missing matches remain explicitly unknown. No value is written
to `transshipmentPort`, `ArrivalEstimate`, connection windows, risk, alerts,
or shipment records.

Production expansion requires approval of MPA reference reuse, formal API
namespace compatibility, location-to-port policy, visit-identity rules, and
evidence-retention ownership.
