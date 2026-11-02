package com.drift.backend.maritimechange;

/**
 * A shipment the viewer may see that belongs in the candidate bundle for one evidence change.
 * {@code vesselSpecificImpact} is true only for a verified vessel association.
 */
public record MaritimeShipmentCandidate(
		long shipmentId,
		long owningCompanyId,
		String vesselRole,
		String association,
		boolean vesselSpecificImpact,
		String activityCompatibility,
		String locationCompatibility,
		String timeCompatibility) {
}
