# Sorting the shipment list by risk (CDG-186)

This records how the shipment list sorts by risk, given that risk is calculated whenever a
shipment is read and is not stored. It covers the at-risk story and must stay in line with
sorting and paging of the shipment list (CDG-162).

## How risk is calculated

`ShipmentRisk.of` derives risk from the connection window that `ConnectionWindowService`
calculates from `plannedMotherArrivalAt` and `plannedFeederDepartureAt`:

| Connection window | Risk |
| --- | --- |
| Either time missing | none (shown as "Not assessed") |
| Feeder departs at or before the mother vessel arrives | CRITICAL |
| Under 12 hours | HIGH |
| Under 24 hours | MEDIUM |
| 24 hours or more | LOW |

Risk uses only these two stored times, not the clock or live AIS positions. A shipment's
risk therefore changes only when its schedule is edited.

## Approach

1. **One calculation.** List items carry a `risk` built with the same `ShipmentRisk.of` call
   as the shipment detail page, so the list and detail page always show the same risk.
2. **Sort in memory, after authorisation and filters.** `ShipmentService.list` loads every
   shipment that matches `ShipmentSpecifications.matching`, which already applies company
   authorisation, status and the other list filters. It calculates each shipment's risk,
   then sorts with one shared comparator:
   - risk, Critical first: CRITICAL, HIGH, MEDIUM, LOW, then not assessed;
   - then the soonest `plannedFeederDepartureAt`, with missing times last;
   - then `id`, so the order is the same on every request.
3. **At-risk filter.** Filtering to HIGH and CRITICAL uses the calculated risk and runs
   before sorting.
4. **Paging.** When the list is paged and sorted by risk, page after sorting: slice the
   sorted list and return the total count. Do not page in the database and then sort each
   page by risk, because that orders each page on its own instead of the whole list. Sorts
   on stored columns, such as `createdAt`, can still page in the database. The request
   parameters (`sort`, `page`, `size`) are shared with CDG-162.

## When to store risk instead

Sorting by risk loads every shipment the user can see on each request. Revisit this
approach when one company can see more than about 5,000 active shipments, or when the
list endpoint's 95th-percentile response time goes over about 500 ms. Both limits are
estimates, not measurements. Current volumes are in the tens to hundreds.

Because risk depends only on stored times, it can be stored without a background job:
add a `risk_level` column that is recalculated whenever a shipment is created or its
schedule changes, and index it with `planned_feeder_departure_at`. The database can then
sort and page directly. A test should check that the stored value always equals
`ShipmentRisk.of`.

## Out of scope

Manual priority overrides, assigning shipments to team members, and impact scoring.
