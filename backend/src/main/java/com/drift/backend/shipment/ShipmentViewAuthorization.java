package com.drift.backend.shipment;

/** Current company visibility for a shipment, captured from the authenticated account and shipment row. */
public record ShipmentViewAuthorization(Long viewerCompanyId, Long managingCompanyId, Long importerCompanyId) {
}
