package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.drift.backend.ais.feed.AisFeedStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Recent or cutoff-bounded track of one shipment vessel from stored observations or a verified CDG-285 capture. "
		+ "Observations flagged as invalid positions are left out; receipt time is not original AIS event time.")
public record VesselTrackResponse(
		@Schema(example = "MOTHER")
		TrackVessel vessel,
		@Schema(description = "Vessel name stored on the shipment. Null when no feeder vessel is assigned.",
				types = { "string", "null" }, example = "MV Pacific Horizon")
		String vesselName,
		@Schema(description = "MMSI of the AIS vessel matched to that name or explicitly stored on the shipment for cutoff retrieval. Null when no identity is available.",
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
		List<TrackPoint> points,
		@Schema(description = "Optional deterministic cutoff. All returned observations have ingestedAt at or before this instant.",
				types = { "string", "null" }, example = "2026-10-30T12:01:00Z")
		Instant cutoff,
		@Schema(description = "The timestamp is DRIFT receipt time, not the original AIS event time.", example = "DRIFT_RECEIPT_TIME")
		String timestampSemantics,
		@Schema(description = "Provenance context for the returned track.", example = "RECORDED")
		TrackContext context,
		@Schema(description = "Track availability relative to the requested cutoff and current AIS feed.", example = "STALE")
		TrackStatus status,
		@Schema(description = "Current global AIS feed status; it does not change recorded track contents.", example = "OFFLINE")
		AisFeedStatus.Status feedStatus,
		@Schema(description = "True when the track window is empty and lastKnownPosition is supplied from earlier retained history.")
		boolean fallbackUsed,
		@Schema(description = "Latest retained position not flagged invalid at or before the cutoff, including a stale last-known fallback.",
				types = { "object", "null" }, implementation = TrackPoint.class)
		TrackPoint lastKnownPosition,
		@Schema(description = "Verified CDG-285 capture id when authentic recorded playback was requested.",
				types = { "string", "null" })
		String captureId) {
}
