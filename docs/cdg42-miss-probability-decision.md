# CDG-42 feeder-connection miss probability: decision record

This record answers the seven CDG-208 questions for CDG-42 ("show users who can
view a shipment the chance that the mother vessel misses the feeder
connection"). It is a decision only: no database change, API, page, or
production code. CDG-209 implements the backend calculation from this record;
CDG-210 shows it on shipment detail.

The Jira story calls this a "miss probability". With the suggested threshold
`T_h = 0h`, what is actually calculated is narrower: the chance that the mother
vessel arrives after the planned feeder departure (see [Q1](#q1-what-does-miss-mean)).
The user-facing wording says exactly that.

## Context

- The only live arrival time DRIFT has is the straight-line estimate from
  `ArrivalEstimateCalculator`: `estimatedArrivalAt = ingestedAt + distance / speed`,
  where the distance is the great-circle distance from the latest retained AIS
  position to the transshipment port. A speed below 0.5 knots cannot be used
  (exactly 0.5 can). A position is `possiblyOutdated` only when it is strictly
  more than 6 hours old (exactly 6 hours is not outdated).
- When the estimate cannot be produced it carries one of five codes, checked in
  this order: `NO_POSITION`, `INVALID_COORDINATES`, `MISSING_PORT`,
  `UNKNOWN_PORT`, `UNUSABLE_SPEED`. The AIS "not available" speed value 102.3 is
  parsed to an empty speed by `AisPositionParser` and so ends as
  `UNUSABLE_SPEED`.
- `ShipmentConnectionRisk` produces no estimate at all (`arrivalComparison` is
  `null`) when the planned mother-vessel arrival is not set.
- `ConnectionWindowService.remaining` uses the estimate only when it is
  available and not `possiblyOutdated`. A gap of zero or less is `MISSED`.
  `ShipmentRisk` maps the remaining window to HIGH (< 12h), MEDIUM (< 24h), LOW,
  or CRITICAL (MISSED). It falls back to the planned window only when there is
  no remaining window; a non-positive planned window is then CRITICAL.
- `AssessmentConfidence` / `AssessmentExplanation` (CDG-51) describe how far the
  risk assessment can be trusted. That is a different concept from this
  probability, and the two must not share wording.
- CDG-201 is Done but pivoted to showing evidence of MPA arrival-time changes
  (`cdg201/` is a read-only replay of a fixed sample). It does not predict
  arrival, so CDG-42 does not wait for it and does not use MPA instants (see
  [Relationship to CDG-201 / CDG-246 / CDG-247](#relationship-to-cdg-201--cdg-246--cdg-247)).
- DRIFT stores no actual arrival time. Neither the planned arrival nor the
  straight-line estimate is an actual arrival.

## Decisions

### Q1. What does "miss" mean?

The event is that the mother vessel's actual arrival is later than the planned
feeder departure minus a handling threshold:

`late ⇔ actual arrival > D − T_h`

`D` is the planned feeder departure. `T_h` is a defined threshold, suggested
value **0h**.

With `T_h = 0h`, the probability is the chance that the mother vessel arrives
after the planned feeder departure. It is not the full chance that the cargo
misses its transshipment: arriving before the feeder departs does not mean
there is enough time to discharge, move, and load the cargo. `T_h = 0h` is a
stated interim baseline, not a claim that zero handling time is realistic. A
value for `T_h` will be chosen once there is data to support one (see
[Open questions](#open-questions)).

With `T_h = 0h`, a 50% chance sits exactly on the existing remaining-window
boundary (remaining = 0 is `MISSED`), so the probability and the remaining
window never contradict each other. A larger `T_h` is listed under
[Alternatives considered](#alternatives-considered).

### Q2. Which arrival time is used?

The existing point estimate `estimatedArrivalAt` (`Â`) is the centre of the
arrival distribution. The planned mother-vessel arrival does not enter the
calculation, and neither do MPA times. CDG-42 does not wait for CDG-201 because
CDG-201 did not deliver an arrival prediction.

### Q3. What happens to a position more than 6 hours old?

It is not used. The probability is unavailable with `POSITION_OUTDATED`. The
calculation reads the existing `possiblyOutdated` flag and does not re-derive the
6-hour rule. A position exactly 6 hours old is used as normal.

### Q4. What model and scale?

A normal distribution around `Â` whose spread grows with remaining voyage and
position age; see [Model](#model). The result is a raw probability in [0, 1]
plus a display value rounded to the nearest 5%; see
[Display and wording](#display-and-wording).

`MISSED` does not mean 100%: a remaining window that is just missed shows about
50%. An invalid planned window (feeder departure at or before the planned
mother-vessel arrival) is not the probability either: the probability is
calculated as normal and a separate `plannedWindowInvalid` flag is returned.

### Q5. What is shown when the probability is unavailable?

`Chance of arriving after feeder departure unavailable: ` followed by:

| Code | Suffix |
| --- | --- |
| `MISSING_PLANNED_MOTHER_ARRIVAL` | the planned mother-vessel arrival is not set, so no arrival estimate exists. |
| `MISSING_FEEDER_DEPARTURE` | the planned feeder departure is not set. |
| `FEEDER_DEPARTURE_PASSED` | the planned feeder departure has already passed, and DRIFT cannot confirm whether the connection was made. |
| `NO_POSITION` | no position has been received for the mother vessel. |
| `INVALID_COORDINATES` | the vessel or port position cannot be used. |
| `MISSING_PORT` | the transshipment port is not set for this shipment. |
| `UNKNOWN_PORT` | the transshipment port is not on the route map. |
| `UNUSABLE_SPEED` | the vessel's speed is missing or below 0.5 knots, so arrival cannot be estimated. |
| `POSITION_OUTDATED` | the latest vessel position is more than 6 hours old. |

`MISSING_PLANNED_MOTHER_ARRIVAL` is new and corresponds to
`arrivalComparison == null`. `FEEDER_DEPARTURE_PASSED` is new (see below). The
five estimate codes reuse the `ArrivalEstimate` codes; their suffixes are new
text for this probability and do not replace the existing estimate or
assessment messages.

**Planned feeder departure already passed.** When `now` is strictly later than
`D`, the probability is unavailable with `FEEDER_DEPARTURE_PASSED`. DRIFT stores
no actual arrival or departure, so after `D` it cannot tell whether the
connection was made, and a forward-looking chance would be misleading. When
`now` equals `D` exactly, the probability is calculated as normal.

Only one reason is returned, in this order:

1. `MISSING_PLANNED_MOTHER_ARRIVAL`;
2. `MISSING_FEEDER_DEPARTURE`;
3. `FEEDER_DEPARTURE_PASSED`;
4. the estimate's own unavailable reason (`NO_POSITION`, `INVALID_COORDINATES`,
   `MISSING_PORT`, `UNKNOWN_PORT`, `UNUSABLE_SPEED`, in the estimate's order);
5. `POSITION_OUTDATED`.

The first two follow CDG-51: `AssessmentConfidence` / `AssessmentExplanation`
name a missing planned mother-vessel arrival before a missing planned
feeder-vessel departure. When both are missing CDG-51 lists both reasons, while
CDG-42 returns a single code, so it returns the first one; the order is the
same. The two otherwise differ on purpose: when the estimate is unavailable or
the position is outdated, CDG-51 falls back to the planned window with a
limited confidence, whereas CDG-42 depends on the live estimate and returns
unavailable.

### Q6. Is anything stored?

No. The probability is calculated on read, like the connection risk and the
remaining window. No table, column, or migration.

### Q7. Who can see it?

Anyone who can view the shipment, under the existing view rule
(`ShipmentAccessPolicy.canView`, enforced for reads by
`ShipmentVisibilityFilter`). The managing company and the linked importer see
the same result. A company that cannot view the shipment sees nothing.

## Model

Times are in hours.

- `t₀` is the time the latest AIS position was stored
  (`ArrivalEstimate.lastReceivedAt`, i.e. `ingestedAt`). It is the reference
  time of the estimate, not the evaluation time.
- `now` is the evaluation time, the same clock used to judge
  `possiblyOutdated` and the passed feeder departure.

| Symbol | Definition |
| --- | --- |
| `τ` | `Â − t₀`, remaining voyage hours measured from the observation (equals distance / speed) |
| `a` | `max(0, now − t₀)`, position age |
| `r` | `(D − T_h) − Â`, margin |
| `σ` | `√(σ₀² + (c_v·τ)² + (k_age·a)²)` |
| `P` | `Φ(−r / σ)`, chance of arriving after `D − T_h` |

`τ` is measured from `t₀`, not from `now`. The time between `t₀` and `now` is
already covered by the separate `k_age·a` term, so measuring `τ` from `now`
would count it twice. In the base scenario below, `τ = 48h`, not 47h; using 47h
gives `σ = 5.114h`, which is the wrong convention (the correct value is
5.206h).

`Φ` is the standard normal CDF. Java has no `erf` in the standard library, so
CDG-209 uses the Abramowitz–Stegun 7.1.26 approximation
(`Φ(z) = ½(1 + erf(z/√2))`, absolute `erf` error below 1.5 × 10⁻⁷).

Parameters, all suggested values and none calibrated:

| Parameter | Suggested value | Meaning |
| --- | --- | --- |
| `T_h` | 0h | Handling time needed before feeder departure (interim baseline) |
| `σ₀` | 2.0h | Base spread for any estimate |
| `c_v` | 0.10 | Spread per hour of remaining voyage (speed variation) |
| `k_age` | 0.25 h/h | Spread per hour of position age |

## Display and wording

- Round the raw probability to the nearest 5%, half-up: `floor(P × 20 + 0.5) × 5`.
- A rounded 0 shows `<5%`; a rounded 100 shows `>95%`. 0% and 100% are never
  shown, even when the raw value is exactly 0 or 1 at double precision.
- Label: `Chance of arriving after feeder departure: 35% · rough estimate`.
  The `rough estimate` marker is required wherever the value is shown.
- Unavailable: `Chance of arriving after feeder departure unavailable: ` plus
  the suffix from [Q5](#q5-what-is-shown-when-the-probability-is-unavailable).
- Neutral style: no badge and no red/amber/green colour. The text never uses
  LOW, MEDIUM, HIGH, CRITICAL, MISSED, or "risk", and does not reuse the
  `AssessmentConfidence` confidence sentences.
- The API returns the raw probability alongside the display value, plus
  `plannedWindowInvalid`.

## Worked examples

Base scenario: `now = 2026-10-26T12:00Z`, `t₀ = 2026-10-26T11:00Z` (`a = 1h`),
720 nautical miles straight-line from the port at 15 knots, so `τ = 48h` and
`Â = 2026-10-28T11:00Z`. Planned mother-vessel arrival `2026-10-28T09:00Z` and
`D = 2026-10-28T13:00Z` unless the row says otherwise.

Short-distance scenario (rows 7, 17, 18): same `now` and `t₀`, 1 nautical mile
from the port at 0.5 knots, so `τ = 2h` and `Â = 2026-10-26T13:00Z`.

Parameters are the suggested values. These rows are the expected values for
CDG-209 tests. "Existing" columns show today's remaining window and
`ShipmentRisk` for the same inputs, for comparison only.

| # | Case | Changed inputs | `σ` (h) | `r` (h) | Raw P | Shown | Existing remaining / risk | `plannedWindowInvalid` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Margin +48h | `D = 2026-10-30T11:00Z` | 5.206 | +48.000 | 0.0000 | `<5%` | AVAILABLE 48h / LOW | false |
| 2 | Margin +12h | `D = 2026-10-28T23:00Z` | 5.206 | +12.000 | 0.0106 | `<5%` | AVAILABLE 12h / MEDIUM | false |
| 3 | Margin +2h (base) | — | 5.206 | +2.000 | 0.3504 | `35%` | AVAILABLE 2h / HIGH | false |
| 4 | Margin exactly 0 | `D = 2026-10-28T11:00Z` | 5.206 | 0.000 | 0.5000 | `50%` | MISSED by 0h / CRITICAL | false |
| 5 | Missed by 3h | `D = 2026-10-28T08:00Z`, planned arrival `2026-10-28T04:00Z` | 5.206 | −3.000 | 0.7178 | `70%` | MISSED by 3h / CRITICAL | false |
| 6 | Missed by 10h | `D = 2026-10-28T01:00Z`, planned arrival `2026-10-27T21:00Z` | 5.206 | −10.000 | 0.9726 | `95%` | MISSED by 10h / CRITICAL | false |
| 7 | Speed exactly 0.5 kn, short distance | short-distance scenario, planned arrival `2026-10-26T13:00Z`, `D = 2026-10-26T15:00Z` | 2.025 | +2.000 | 0.1617 | `15%` | AVAILABLE 2h / HIGH | false |
| 8 | Speed exactly 0.5 kn, numeric edge | speed 0.5 kn at 720 nm (`τ = 1440h`, `Â = 2026-12-25T11:00Z`) | 144.014 | −1390.000 | 1.0000 | `>95%` | MISSED by 1390h / CRITICAL | false |
| 9 | Speed 0.4 kn | speed 0.4 kn | — | — | — | unavailable: `UNUSABLE_SPEED` | none / HIGH (planned) | — |
| 10 | Position exactly 6h old | `now = 2026-10-26T17:00Z` (`a = 6h`) | 5.412 | +2.000 | 0.3559 | `35%` | AVAILABLE 2h / HIGH | false |
| 11 | Position 6h + 1s old | `now = 2026-10-26T17:00:01Z` | — | — | — | unavailable: `POSITION_OUTDATED` | none / HIGH (planned) | — |
| 12 | Feeder departure not set | `D` not set | — | — | — | unavailable: `MISSING_FEEDER_DEPARTURE` | none / none | — |
| 13 | Planned mother arrival not set | planned arrival not set | — | — | — | unavailable: `MISSING_PLANNED_MOTHER_ARRIVAL` | none / none | — |
| 14 | Both planned times not set | `D` and planned arrival not set | — | — | — | unavailable: `MISSING_PLANNED_MOTHER_ARRIVAL` | none / none | — |
| 15 | No position | no AIS position | — | — | — | unavailable: `NO_POSITION` | none / HIGH (planned) | — |
| 16 | Planned window invalid, live margin +12h | `D = 2026-10-28T23:00Z`, planned arrival `2026-10-29T00:00Z` | 5.206 | +12.000 | 0.0106 | `<5%` | AVAILABLE 12h / MEDIUM | true |
| 17 | `now` exactly at feeder departure | short-distance scenario, planned arrival `2026-10-26T11:00Z`, `D = 2026-10-26T12:00Z` | 2.025 | −1.000 | 0.6892 | `70%` | MISSED by 1h / CRITICAL | false |
| 18 | `now` 1s after feeder departure | short-distance scenario, planned arrival `2026-10-26T11:00Z`, `D = 2026-10-26T11:59:59Z` | — | — | — | unavailable: `FEEDER_DEPARTURE_PASSED` | MISSED by 1h 0m 1s / CRITICAL | — |

Notes for tests:

- Row 1's raw value underflows to 0 and row 8's rounds to 1 at double
  precision; the display still shows `<5%` / `>95%`.
- Row 4 shows that 50% coincides with the existing `MISSED` "0 seconds" case.
- Row 7 is the main 0.5-knot example. Row 8 keeps the long-distance 0.5-knot
  case only as a numeric edge case.
- Row 14 checks the reason order: the missing planned mother-vessel arrival
  wins, as in CDG-51.
- Row 16 shows that an invalid planned window does not change the probability.
- Rows 17 and 18 check the passed-departure boundary: equal is calculated,
  strictly later is unavailable. The existing risk is unchanged by this rule.
- A real calculator run will not land on exactly 720 or 1 nautical miles unless
  the position is placed 12° or 1′ of latitude due south of the port on the
  same meridian. Compare raw probabilities with a tolerance of 1 × 10⁻³, or test
  the probability step with a fixed `Â`.

## Relationship to CDG-201 / CDG-246 / CDG-247

- CDG-42 uses only the AIS-based straight-line estimate. It does not use MPA
  times. This answers, for CDG-42, the source-precedence question left open in
  [the replay record](cdg201-readonly-replay.md): AIS is the only input; MPA
  instants are not used.
- CDG-201 shows MPA ETA changes read-only. CDG-246 gathers maritime evidence.
  CDG-247 interprets that evidence and suggests a manual check.
- These outputs rest on different evidence, so they can disagree, and that is
  expected. CDG-247 does not override the CDG-42 probability, and the MPA replay
  is not a validated input to this model.
- The existing risk level calculation (`ConnectionWindowService`,
  `ShipmentRisk`) is not affected by CDG-42.

## Extension seam

CDG-209 puts the distribution behind an `ArrivalDistribution` interface. Today's
implementation is `StraightLineNormalArrival` (centre `Â`, spread `σ` above).
When a real arrival prediction exists, it replaces that implementation; the API
fields and the page do not change.

## Out of scope for CDG-209

- Replacing `c_v` with speed variation measured from AIS history (a later
  improvement).
- Correcting for route detours relative to the straight line.
- Extra penalty for low speeds.

## Limitations

- This is a statistical model whose parameters are assumptions. It is not a
  trained model, not machine learning, and not a Monte Carlo simulation. It has
  not been calibrated: 35% does not mean that 35 of 100 similar past shipments
  arrived late. The UI must always show the `rough estimate` marker.
- The event is late arrival relative to `D − T_h`, not a confirmed cargo
  transshipment failure; with `T_h = 0h` handling time is ignored.
- The straight-line distance underestimates the real voyage, so the estimate
  is early and the probability is biased low.
- The normal distribution is symmetric; real delays skew late.
- There is no calibration data; the parameters are judgement, not fitted.
- The planned feeder departure is treated as fixed.

## Open questions

- What value should `T_h` take? It stays 0h as an interim baseline until there
  is data on handling times.

Resolved:

- Planned feeder departure already passed: the probability is unavailable with
  `FEEDER_DEPARTURE_PASSED` when `now` is strictly later than `D` (see
  [Q5](#q5-what-is-shown-when-the-probability-is-unavailable)).

## Alternatives considered

The Gemini proposal used an 8h handling time (`T_h = 8h`), a bias term `B` that
shifts the estimate later, a wider `σ`, and a hard 5–95% clamp. It was not
chosen because, combined, those choices saturate at 95% for most tight windows,
so the number stops separating shipments, and `B` has no upper bound. An 8h
`T_h` stays available as a team option (see [Open questions](#open-questions));
with it, 50% would no longer line up with the remaining-window `MISSED`
boundary.
