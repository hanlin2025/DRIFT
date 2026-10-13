package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The latest retained mother-vessel position used to estimate arrival.
 * {@code ingestedAt} is when DRIFT stored the report.
 */
public record ArrivalFix(
		BigDecimal latitude,
		BigDecimal longitude,
		BigDecimal speedOverGroundKnots,
		Instant ingestedAt) {

	static ArrivalFix from(VesselPosition position) {
		if (position == null) {
			return null;
		}
		return new ArrivalFix(position.latitude(), position.longitude(), position.speedOverGroundKnots(),
				position.ingestedAt());
	}
}
