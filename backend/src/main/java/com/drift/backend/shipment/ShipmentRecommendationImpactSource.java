package com.drift.backend.shipment;

import org.springframework.stereotype.Component;

/**
 * Impact for the shipment read and for a decision on that same read.
 * No assessment is stored, so the impact stays unavailable.
 */
@Component
class ShipmentRecommendationImpactSource {

	ShipmentRecommendationImpact current() {
		return ShipmentRecommendationImpact.notReturned();
	}
}
