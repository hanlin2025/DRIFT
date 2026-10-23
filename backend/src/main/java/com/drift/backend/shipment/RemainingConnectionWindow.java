package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Remaining time from the mother vessel's estimated arrival to the planned feeder departure.
 * Calculated when a shipment is read and not stored. Absent when the estimate cannot be used.
 */
@Schema(description = "Remaining time from the mother vessel's estimated arrival to the planned feeder departure. Calculated when the shipment is read and not stored.")
public record RemainingConnectionWindow(
		@Schema(description = "AVAILABLE when time remains. MISSED when the estimated arrival is at or after the planned feeder departure.", example = "AVAILABLE")
		Status status,
		@Schema(description = "Understandable remaining duration, such as \"6 hours\". A positive gap under one second is \"less than 1 second\". Null when the connection is expected to be missed.", example = "6 hours", nullable = true)
		String duration,
		@Schema(description = "Whole seconds from the estimated mother-vessel arrival to the planned feeder-vessel departure. Zero when that gap is under one second. Null when the connection is expected to be missed.", example = "21600", nullable = true)
		Long totalSeconds,
		@Schema(description = "How long after the planned feeder departure the mother vessel is estimated to arrive. \"0 seconds\" when those times are equal, and \"less than 1 second\" when the overrun is under one second. Null when time remains.", example = "2 hours", nullable = true)
		String missedBy,
		@Schema(description = "Whole seconds by which the estimated arrival is after the planned feeder departure. Zero when those times are equal or the overrun is under one second. Null when time remains.", example = "7200", nullable = true)
		Long missedBySeconds) {

	public enum Status {
		AVAILABLE,
		MISSED
	}

	public RemainingConnectionWindow {
		if (status == null) {
			throw new IllegalArgumentException("Remaining connection window status is required");
		}
		if (status == Status.AVAILABLE) {
			if (duration == null || duration.isBlank() || totalSeconds == null || totalSeconds < 0
					|| missedBy != null || missedBySeconds != null) {
				throw new IllegalArgumentException("An available remaining window has a duration and no missed amount");
			}
		} else if (duration != null || totalSeconds != null || missedBy == null || missedBy.isBlank()
				|| missedBySeconds == null || missedBySeconds < 0) {
			throw new IllegalArgumentException("A missed remaining window has only the missed amount");
		}
	}

	static RemainingConnectionWindow available(String duration, long totalSeconds) {
		return new RemainingConnectionWindow(Status.AVAILABLE, duration, totalSeconds, null, null);
	}

	static RemainingConnectionWindow missed(String missedBy, long missedBySeconds) {
		return new RemainingConnectionWindow(Status.MISSED, null, null, missedBy, missedBySeconds);
	}
}
