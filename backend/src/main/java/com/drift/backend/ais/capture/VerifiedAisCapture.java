package com.drift.backend.ais.capture;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.drift.backend.shipment.TrackPoint;

public record VerifiedAisCapture(
		String captureId,
		String vesselRole,
		String vesselName,
		String mmsi,
		Instant from,
		Instant to,
		LocalDate retainUntil,
		List<TrackPoint> points) {
}
