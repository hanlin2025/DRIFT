package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.shipment.exception.InvalidShipmentRequestException;

/**
 * Track history for the mother or feeder vessel of a visible shipment.
 * Visibility and the vessel's MMSI come from the same lookup as {@code /tracking}.
 */
@Service
public class VesselTrackService {

	public static final int DEFAULT_WINDOW_HOURS = 24;
	public static final Set<Integer> WINDOW_HOURS = Set.of(6, 24, 72);
	public static final int MAX_POINTS = 500;
	public static final BigDecimal SLOWDOWN_THRESHOLD_KNOTS = BigDecimal.valueOf(3);

	static final String INVALID_VESSEL = "Vessel must be MOTHER or FEEDER";
	static final String INVALID_WINDOW = "Window must be 6, 24 or 72 hours";

	private final ShipmentService shipments;
	private final LatestAisPositions positions;
	private final Clock clock;

	@Autowired
	public VesselTrackService(ShipmentService shipments, LatestAisPositions positions) {
		this(shipments, positions, Clock.systemUTC());
	}

	VesselTrackService(ShipmentService shipments, LatestAisPositions positions, Clock clock) {
		this.shipments = shipments;
		this.positions = positions;
		this.clock = clock;
	}

	public VesselTrackResponse history(AuthenticatedUser principal, Long shipmentId, String vessel, String hours) {
		TrackVessel which = vessel(vessel);
		int windowHours = windowHours(hours);
		ShipmentTrackingResponse tracking = shipments.tracking(principal, shipmentId);
		String name = which == TrackVessel.MOTHER ? tracking.motherVesselName() : tracking.feederVesselName();
		VesselPosition latest = which == TrackVessel.MOTHER ? tracking.motherVessel() : tracking.feederVessel();
		Instant to = clock.instant();
		Instant from = to.minus(Duration.ofHours(windowHours));
		String mmsi = latest == null ? null : latest.mmsi();
		List<AisPosition> newestFirst = mmsi == null ? List.of() : positions.history(mmsi, from, to, MAX_POINTS + 1);
		boolean truncated = newestFirst.size() > MAX_POINTS;
		List<TrackPoint> points = new ArrayList<>(newestFirst.subList(0, Math.min(newestFirst.size(), MAX_POINTS))
				.stream().map(TrackPoint::from).toList());
		Collections.reverse(points);
		return new VesselTrackResponse(which, name, mmsi, windowHours, from, to, SLOWDOWN_THRESHOLD_KNOTS,
				MAX_POINTS, truncated, List.copyOf(points));
	}

	private static TrackVessel vessel(String raw) {
		if (raw == null || raw.isBlank()) {
			return TrackVessel.MOTHER;
		}
		try {
			return TrackVessel.valueOf(raw.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw new InvalidShipmentRequestException(Map.of("vessel", INVALID_VESSEL));
		}
	}

	private static int windowHours(String raw) {
		if (raw == null || raw.isBlank()) {
			return DEFAULT_WINDOW_HOURS;
		}
		String value = raw.strip();
		if (!value.matches("\\d{1,3}") || !WINDOW_HOURS.contains(Integer.parseInt(value))) {
			throw new InvalidShipmentRequestException(Map.of("hours", INVALID_WINDOW));
		}
		return Integer.parseInt(value);
	}
}
