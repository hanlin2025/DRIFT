package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.ais.capture.RecordedAisCaptureReader;
import com.drift.backend.ais.capture.VerifiedAisCapture;
import com.drift.backend.ais.feed.AisFeedStatus;
import com.drift.backend.ais.feed.AisFeedStatusService;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.ais.position.StoredAisPosition;
import com.drift.backend.shipment.exception.InvalidShipmentRequestException;

/** Shipment-authorized track history and bounded, read-only cutoff retrieval. */
@Service
public class VesselTrackService {

	public static final int DEFAULT_WINDOW_HOURS = 24;
	public static final Set<Integer> WINDOW_HOURS = Set.of(6, 24, 72);
	public static final int MAX_POINTS = 500;
	public static final BigDecimal SLOWDOWN_THRESHOLD_KNOTS = BigDecimal.valueOf(3);
	private static final Duration MAX_HISTORY = Duration.ofHours(72);
	private static final Duration STALE_AFTER = Duration.ofHours(1);
	private static final Duration LIVE_FOR = Duration.ofMinutes(5);
	private static final String RECEIPT_TIME = "DRIFT_RECEIPT_TIME";

	static final String INVALID_VESSEL = "Vessel must be MOTHER or FEEDER";
	static final String INVALID_WINDOW = "Window must be 6, 24 or 72 hours";
	static final String INVALID_CUTOFF = "Cutoff must be an ISO-8601 timestamp with an offset and cannot be in the future";
	static final String INVALID_CAPTURE = "A capture id requires a valid cutoff";
	static final String AUTHENTIC_CAPTURE_REQUIRED = "AIS_STREAM playback requires an approved capture manifest";

	private final ShipmentService shipments;
	private final LatestAisPositions positions;
	private final RecordedAisCaptureReader captures;
	private final AisFeedStatusService feedStatus;
	private final Clock clock;

	@Autowired
	public VesselTrackService(ShipmentService shipments, LatestAisPositions positions,
			RecordedAisCaptureReader captures, AisFeedStatusService feedStatus, ObjectProvider<Clock> clocks) {
		this(shipments, positions, captures, feedStatus, clocks.getIfAvailable(Clock::systemUTC));
	}

	VesselTrackService(ShipmentService shipments, LatestAisPositions positions,
			RecordedAisCaptureReader captures, AisFeedStatusService feedStatus, Clock clock) {
		this.shipments = shipments;
		this.positions = positions;
		this.captures = captures;
		this.feedStatus = feedStatus;
		this.clock = clock;
	}

	public VesselTrackResponse history(AuthenticatedUser principal, Long shipmentId, String vessel, String hours) {
		return history(principal, shipmentId, vessel, hours, null, null);
	}

	public VesselTrackResponse history(AuthenticatedUser principal, Long shipmentId, String vessel, String hours,
			String cutoffRaw, String captureId) {
		TrackVessel which = vessel(vessel);
		int windowHours = windowHours(hours);
		Instant cutoff = parseCutoff(cutoffRaw);
		if (captureId != null && !captureId.isBlank() && cutoff == null) {
			throw invalid("captureId", INVALID_CAPTURE);
		}
		if (cutoff == null) {
			return liveHistory(principal, shipmentId, which, windowHours);
		}
		return cutoffHistory(principal, shipmentId, which, windowHours, cutoff,
				captureId == null || captureId.isBlank() ? null : captureId.strip());
	}

	private VesselTrackResponse liveHistory(AuthenticatedUser principal, Long shipmentId, TrackVessel which,
			int windowHours) {
		ShipmentTrackingResponse tracking = shipments.tracking(principal, shipmentId);
		String name = which == TrackVessel.MOTHER ? tracking.motherVesselName() : tracking.feederVesselName();
		VesselPosition latest = which == TrackVessel.MOTHER ? tracking.motherVessel() : tracking.feederVessel();
		Instant to = clock.instant();
		Instant from = to.minus(Duration.ofHours(windowHours));
		String mmsi = latest == null ? null : latest.mmsi();
		List<StoredAisPosition> stored = mmsi == null ? List.of()
				: positions.history(mmsi, from, to, MAX_POINTS + 1);
		boolean truncated = stored.size() > MAX_POINTS;
		List<TrackPoint> points = newestFirstToBoundedChronology(stored);
		TrackPoint lastKnown = points.isEmpty() ? null : points.getLast();
		AisFeedStatus currentFeed = feedStatus.current();
		TrackContext context = context(points, cutoffIsLive(latest, to));
		return response(which, name, mmsi, windowHours, from, to, truncated, points, null, context,
				availability(lastKnown, to, currentFeed), currentFeed, false, lastKnown, null);
	}

	private VesselTrackResponse cutoffHistory(AuthenticatedUser principal, Long shipmentId, TrackVessel which,
			int windowHours, Instant cutoff, String captureId) {
		// This scoped lookup checks shipment visibility before any manifest path or capture content is read.
		ShipmentTrackIdentity identity = shipments.trackIdentity(principal, shipmentId);
		String name = identity.vesselName(which);
		String imo = identity.imo(which);
		String mmsi = identity.mmsi(which);
		Instant from = cutoff.minus(Duration.ofHours(windowHours));
		List<TrackPoint> available;
		TrackContext context;
		String verifiedCaptureId = null;
		if (captureId != null) {
			VerifiedAisCapture capture = captures.read(captureId, which, imo, mmsi, cutoff);
			available = capture.points();
			context = TrackContext.RECORDED;
			verifiedCaptureId = capture.captureId();
		} else {
			Instant maxFrom = cutoff.minus(MAX_HISTORY);
			if (mmsi != null && positions.hasSourceInWindow(mmsi, maxFrom, cutoff, AisPositionSource.AIS_STREAM)) {
				throw invalid("captureId", AUTHENTIC_CAPTURE_REQUIRED);
			}
			available = mmsi == null ? List.of() : positions.history(mmsi, maxFrom, cutoff, MAX_POINTS + 1,
					AisPositionSource.SEEDED).stream().map(TrackPoint::from).toList();
			context = TrackContext.SEEDED;
		}
		List<TrackPoint> inWindow = available.stream()
				.filter(point -> point.ingestedAt().isAfter(from) && !point.ingestedAt().isAfter(cutoff))
				.sorted(Comparator.comparing(TrackPoint::ingestedAt).thenComparingLong(TrackPoint::id))
				.toList();
		boolean truncated = inWindow.size() > MAX_POINTS;
		List<TrackPoint> points = List.copyOf(inWindow.subList(Math.max(0, inWindow.size() - MAX_POINTS), inWindow.size()));
		TrackPoint lastKnown = available.stream().filter(point -> !point.ingestedAt().isAfter(cutoff))
				.max(Comparator.comparing(TrackPoint::ingestedAt).thenComparingLong(TrackPoint::id)).orElse(null);
		boolean fallback = points.isEmpty() && lastKnown != null;
		AisFeedStatus currentFeed = feedStatus.current();
		return response(which, name, mmsi, windowHours, from, cutoff, truncated, points, cutoff,
				captureId == null ? context : TrackContext.RECORDED,
				availability(lastKnown, cutoff, currentFeed), currentFeed, fallback, lastKnown, verifiedCaptureId);
	}

	private VesselTrackResponse response(TrackVessel vessel, String name, String mmsi, int windowHours,
			Instant from, Instant to, boolean truncated, List<TrackPoint> points, Instant cutoff,
			TrackContext context, TrackStatus status, AisFeedStatus feed, boolean fallback,
			TrackPoint lastKnown, String captureId) {
		return new VesselTrackResponse(vessel, name, mmsi, windowHours, from, to, SLOWDOWN_THRESHOLD_KNOTS,
				MAX_POINTS, truncated, points, cutoff, RECEIPT_TIME, context, status, feed.status(), fallback,
				lastKnown, captureId);
	}

	private static List<TrackPoint> newestFirstToBoundedChronology(List<StoredAisPosition> newestFirst) {
		List<TrackPoint> points = new ArrayList<>(newestFirst.subList(0, Math.min(newestFirst.size(), MAX_POINTS))
				.stream().map(TrackPoint::from).toList());
		points.sort(Comparator.comparing(TrackPoint::ingestedAt).thenComparingLong(TrackPoint::id));
		return List.copyOf(points);
	}

	private static TrackContext context(List<TrackPoint> points, boolean livePosition) {
		boolean seeded = points.stream().anyMatch(point -> point.source() == AisPositionSource.SEEDED);
		boolean recorded = points.stream().anyMatch(point -> point.source() == AisPositionSource.AIS_STREAM);
		if (seeded && recorded) return TrackContext.MIXED;
		if (seeded) return TrackContext.SEEDED;
		if (recorded) return livePosition ? TrackContext.LIVE_POSITION : TrackContext.RECORDED;
		return TrackContext.UNAVAILABLE;
	}

	private static boolean cutoffIsLive(VesselPosition latest, Instant to) {
		return latest != null && latest.source() == AisPositionSource.AIS_STREAM
				&& !latest.ingestedAt().isBefore(to.minus(LIVE_FOR)) && !latest.ingestedAt().isAfter(to);
	}

	private static TrackStatus availability(TrackPoint lastKnown, Instant cutoff, AisFeedStatus feed) {
		if (lastKnown == null) return TrackStatus.UNAVAILABLE;
		if (feed.status() == AisFeedStatus.Status.OFFLINE) return TrackStatus.OFFLINE;
		if (Duration.between(lastKnown.ingestedAt(), cutoff).compareTo(STALE_AFTER) > 0) return TrackStatus.STALE;
		return TrackStatus.AVAILABLE;
	}

	private Instant parseCutoff(String raw) {
		if (raw == null || raw.isBlank()) return null;
		try {
			Instant cutoff = OffsetDateTime.parse(raw.strip()).toInstant();
			if (cutoff.isAfter(clock.instant())) throw invalid("cutoff", INVALID_CUTOFF);
			return cutoff;
		} catch (DateTimeParseException ex) {
			throw invalid("cutoff", INVALID_CUTOFF);
		}
	}

	private static TrackVessel vessel(String raw) {
		if (raw == null || raw.isBlank()) return TrackVessel.MOTHER;
		try {
			return TrackVessel.valueOf(raw.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw invalid("vessel", INVALID_VESSEL);
		}
	}

	private static int windowHours(String raw) {
		if (raw == null || raw.isBlank()) return DEFAULT_WINDOW_HOURS;
		String value = raw.strip();
		if (!value.matches("\\d{1,3}") || !WINDOW_HOURS.contains(Integer.parseInt(value))) {
			throw invalid("hours", INVALID_WINDOW);
		}
		return Integer.parseInt(value);
	}

	private static InvalidShipmentRequestException invalid(String field, String message) {
		return new InvalidShipmentRequestException(Map.of(field, message));
	}
}
