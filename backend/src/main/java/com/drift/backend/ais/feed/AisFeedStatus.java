package com.drift.backend.ais.feed;

import java.time.Instant;

/**
 * Whether DRIFT is still receiving AIS reports, and when it stored the newest AIS_STREAM report.
 * The result carries no vessel, position, MMSI, or shipment.
 */
public record AisFeedStatus(Status status, Instant lastReceivedAt) {

	public enum Status {
		LIVE,
		DELAYED,
		OFFLINE
	}

	public AisFeedStatus {
		if (status == null) {
			throw new IllegalArgumentException("AIS feed status is required");
		}
		if (lastReceivedAt == null && status != Status.OFFLINE) {
			throw new IllegalArgumentException("A live or delayed AIS feed has a last-received time");
		}
	}
}
