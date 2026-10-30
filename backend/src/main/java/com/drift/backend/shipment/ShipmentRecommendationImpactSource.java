package com.drift.backend.shipment;

import org.springframework.stereotype.Component;

/**
 * The impact the current shipment read uses when it chooses a recommendation.
 * Until a stored assessment is returned, that impact is not available, so a
 * decision against this read is rejected and stores nothing.
 */
@Component
class ShipmentRecommendationImpactSource {

	ShipmentRecommendationImpact current() {
		return ShipmentRecommendationImpact.notReturned();
	}
}
