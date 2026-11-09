package com.drift.backend.shipment;

import org.springframework.stereotype.Component;

/**
 * Impact for the shipment read and for a decision on that same read.
 * The result is the delay comparison already calculated for that shipment.
 * A completed comparison is no predicted impact or an affected deadline.
 * A missing, stale, or incompatible comparison stays unavailable and keeps its reason.
 */
@Component
class ShipmentRecommendationImpactSource {

	ShipmentRecommendationImpact fromComparison(DelayComparison comparison) {
		return ShipmentRecommendationImpact.from(comparison);
	}
}
