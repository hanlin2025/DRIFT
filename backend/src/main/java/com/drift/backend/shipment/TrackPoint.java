package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Instant;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.StoredAisPosition;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One stored AIS observation on a vessel's track.")
public record TrackPoint(
		@Schema(description = "Observation id. Unique for each point, including points that share a timestamp.", example = "184467")
		long id,
		@Schema(example = "1.264")
		BigDecimal latitude,
		@Schema(example = "103.82")
		BigDecimal longitude,
		@Schema(description = "Null when the vessel did not report its speed.", example = "12.4")
		BigDecimal speedOverGroundKnots,
		@Schema(description = "Null when the vessel did not report its course.", example = "175.0")
		BigDecimal courseOverGroundDegrees,
		@Schema(description = "Null when the vessel did not report its heading.", example = "176")
		Integer trueHeadingDegrees,
		Instant ingestedAt) {

	static TrackPoint from(StoredAisPosition stored) {
		AisPosition position = stored.position();
		return new TrackPoint(stored.id(), position.latitude(), position.longitude(), position.speedOverGroundKnots(),
				position.courseOverGroundDegrees(), position.trueHeadingDegrees(), position.ingestedAt());
	}
}
