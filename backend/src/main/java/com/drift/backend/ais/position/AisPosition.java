package com.drift.backend.ais.position;

import java.math.BigDecimal;
import java.time.Instant;

public record AisPosition(
		String mmsi,
		String vesselName,
		BigDecimal latitude,
		BigDecimal longitude,
		BigDecimal speedOverGroundKnots,
		BigDecimal courseOverGroundDegrees,
		Integer trueHeadingDegrees,
		Integer navigationalStatus,
		Boolean positionValid,
		Integer aisUtcSecond,
		Instant ingestedAt) {
}
