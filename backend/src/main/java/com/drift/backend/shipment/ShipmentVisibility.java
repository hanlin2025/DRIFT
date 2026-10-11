package com.drift.backend.shipment;

/** Company ids needed to apply {@link ShipmentAccessPolicy#canView(Long, Long, Long)}. */
public record ShipmentVisibility(long id, long managingCompanyId, Long importerCompanyId) {
}
