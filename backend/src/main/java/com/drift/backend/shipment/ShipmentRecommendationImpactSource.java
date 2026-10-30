package com.drift.backend.shipment;

import org.springframework.stereotype.Component;

/**
 * The impact the current shipment read uses when it chooses a recommendation.
 * Until a stored impact assessment is returned, the production read treats the
 * prediction as having no predicted effect on the importer's constraints.
 */
@Component
class ShipmentRecommendationImpactSource {

	ShipmentRecommendationImpact current() {
		return ShipmentRecommendationImpact.noPredictedImpact();
	}
}
