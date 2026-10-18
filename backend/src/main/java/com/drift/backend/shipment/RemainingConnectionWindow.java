package com.drift.backend.shipment;

/**
 * Remaining time from the mother vessel's estimated arrival to the planned feeder departure.
 * Calculated when a shipment is read and not stored. Absent when the estimate cannot be used.
 */
public record RemainingConnectionWindow(
		Status status,
		String duration,
		Long totalSeconds,
		String missedBy,
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
