package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Instant;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Latest stored AIS observation for a vessel. This is a position fix, not an arrival estimate.")
public record VesselPosition(
		@Schema(example = "563123456")
		String mmsi,
		@Schema(example = "PACIFIC HORIZON")
		String vesselName,
		@Schema(example = "1.264")
		BigDecimal latitude,
		@Schema(example = "103.82")
		BigDecimal longitude,
		@Schema(example = "12.4")
		BigDecimal speedOverGroundKnots,
		@Schema(example = "175.0")
		BigDecimal courseOverGroundDegrees,
		@Schema(example = "176")
		Integer trueHeadingDegrees,
		Instant ingestedAt,
		@Schema(description = "AIS_STREAM for a report received from the livestream, SEEDED for seeded or synthetic data.", example = "AIS_STREAM")
		AisPositionSource source) {

	static VesselPosition from(AisPosition position) {
		return new VesselPosition(position.mmsi(), position.vesselName(), position.latitude(), position.longitude(),
				position.speedOverGroundKnots(), position.courseOverGroundDegrees(), position.trueHeadingDegrees(),
				position.ingestedAt(), position.source());
	}
}
