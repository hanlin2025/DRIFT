package com.drift.backend.maritimechange;

/** A shipment the viewer may see that was left out of the candidate bundle. */
public record MaritimeShipmentExclusion(long shipmentId, long owningCompanyId, String reason) {
}
