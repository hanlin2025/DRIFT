package com.drift.backend.shipment;

import org.springframework.stereotype.Component;

/**
 * The impact the current shipment read uses when it chooses a recommendation.
 * That read has not returned an assessment yet, so a decision against it is unavailable.
 */
@Component
class ShipmentRecommendationImpactSource {

	ShipmentRecommendationImpact current() {
		return ShipmentRecommendationImpact.notReturned();
	}
}
