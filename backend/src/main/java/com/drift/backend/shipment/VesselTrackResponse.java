package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Recent track of one vessel on a shipment, built from stored AIS observations in time order. "
		+ "Observations flagged as invalid positions are left out.")
public record VesselTrackResponse(
		@Schema(example = "MOTHER")
		TrackVessel vessel,
		@Schema(description = "Vessel name stored on the shipment. Null when no feeder vessel is assigned.",
				types = { "string", "null" }, example = "MV Pacific Horizon")
		String vesselName,
		@Schema(description = "MMSI of the AIS vessel matched to that name. Null when no report is retained for it.",
				types = { "string", "null" }, example = "563123456")
		String mmsi,
		@Schema(example = "24")
		int windowHours,
		@Schema(description = "Start of the window, exclusive")
		Instant from,
		@Schema(description = "End of the window, inclusive")
		Instant to,
		@Schema(description = "Segments slower than this speed are slowdowns", example = "3")
		BigDecimal slowdownThresholdKnots,
		@Schema(description = "Most points returned for one window", example = "500")
		int pointLimit,
		@Schema(description = "True when the window held more points than the limit; the newest points are kept")
		boolean truncated,
		@ArraySchema(arraySchema = @Schema(description = "Observations in the window, oldest first"))
		List<TrackPoint> points) {
}
