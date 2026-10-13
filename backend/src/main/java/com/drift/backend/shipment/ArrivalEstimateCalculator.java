package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.drift.backend.shipment.ArrivalEstimate.Classification;
import com.drift.backend.shipment.PortCatalog.Coordinates;

/**
 * Estimates when the mother vessel will reach the transshipment port from its latest retained position.
 * The arrival instant is {@code ingestedAt} plus straight-line travel time. The clock is used only to judge freshness.
 */
public final class ArrivalEstimateCalculator {

	static final BigDecimal MINIMUM_SPEED_KNOTS = new BigDecimal("0.5");
	static final Duration ON_SCHEDULE_LIMIT = Duration.ofHours(2);
	static final Duration OUTDATED_AFTER = Duration.ofHours(6);

	private static final String NO_POSITION = "NO_POSITION";
	private static final String INVALID_COORDINATES = "INVALID_COORDINATES";
	private static final String MISSING_PORT = "MISSING_PORT";
	private static final String UNKNOWN_PORT = "UNKNOWN_PORT";
	private static final String UNUSABLE_SPEED = "UNUSABLE_SPEED";

	private final PortCatalog ports;

	public ArrivalEstimateCalculator(PortCatalog ports) {
		this.ports = ports;
	}

	public ArrivalEstimate estimate(ArrivalFix fix, String transshipmentPort, OffsetDateTime plannedArrival, Clock clock) {
		if (plannedArrival == null || clock == null) {
			throw new IllegalArgumentException("A planned arrival and a clock are required");
		}
		if (fix == null) {
			return ArrivalEstimate.unavailable(NO_POSITION, "No AIS position has been received for the mother vessel.");
		}
		if (unusable(fix.latitude(), fix.longitude()) || fix.ingestedAt() == null) {
			return ArrivalEstimate.unavailable(INVALID_COORDINATES, "The vessel or port coordinates cannot be used.");
		}
		if (transshipmentPort == null || transshipmentPort.isBlank()) {
			return ArrivalEstimate.unavailable(MISSING_PORT, "The transshipment port is missing.");
		}
		Coordinates port = ports.locate(transshipmentPort).orElse(null);
		if (port == null) {
			return ArrivalEstimate.unavailable(UNKNOWN_PORT,
					"The transshipment port is not on the route map, so its coordinates cannot be used.");
		}
		if (unusableSpeed(fix.speedOverGroundKnots())) {
			return ArrivalEstimate.unavailable(UNUSABLE_SPEED,
					"The mother vessel's speed is missing or below 0.5 knots.");
		}

		double nauticalMiles = nauticalMiles(fix.latitude().doubleValue(), fix.longitude().doubleValue(),
				port.latitude(), port.longitude());
		if (!Double.isFinite(nauticalMiles)) {
			return ArrivalEstimate.unavailable(INVALID_COORDINATES, "The vessel or port coordinates cannot be used.");
		}

		Instant estimated = fix.ingestedAt().plus(travelTime(nauticalMiles, fix.speedOverGroundKnots()));
		Duration difference = Duration.between(plannedArrival.toInstant(), estimated);
		Classification classification = classify(difference);
		SignedDifference signed = SignedDifference.from(difference);
		boolean possiblyOutdated = Duration.between(fix.ingestedAt(), clock.instant()).compareTo(OUTDATED_AFTER) > 0;
		return ArrivalEstimate.available(estimated.atOffset(ZoneOffset.UTC), fix.speedOverGroundKnots(), classification,
				differenceText(classification, difference), signed.seconds(), signed.nanos(), fix.ingestedAt(),
				possiblyOutdated);
	}

	private static Classification classify(Duration difference) {
		if (difference.isNegative()) {
			return Classification.AHEAD_OF_PLAN;
		}
		if (difference.compareTo(ON_SCHEDULE_LIMIT) <= 0) {
			return Classification.ON_SCHEDULE;
		}
		return Classification.BEHIND_PLAN;
	}

	private static String differenceText(Classification classification, Duration difference) {
		if (difference.isZero()) {
			return "On schedule · estimated at the planned time";
		}
		String direction = difference.isNegative() ? "earlier" : "later";
		String label = switch (classification) {
			case AHEAD_OF_PLAN -> "Ahead of plan";
			case ON_SCHEDULE -> "On schedule";
			case BEHIND_PLAN -> "Behind plan";
		};
		return label + " · estimated " + magnitude(classification, difference.abs()) + " " + direction + " than planned";
	}

	private static String magnitude(Classification classification, Duration absolute) {
		if (absolute.compareTo(Duration.ofSeconds(1)) < 0) {
			return "less than 1s";
		}
		if (classification == Classification.BEHIND_PLAN
				&& absolute.compareTo(ON_SCHEDULE_LIMIT) > 0
				&& absolute.compareTo(ON_SCHEDULE_LIMIT.plusSeconds(1)) < 0) {
			return "just over 2h";
		}
		if (absolute.getNano() != 0) {
			return formatExact(absolute);
		}
		long roundedMinutes = (absolute.getSeconds() + 30) / 60;
		if (roundedMinutes == 0) {
			return absolute.getSeconds() + "s";
		}
		Duration rounded = Duration.ofMinutes(roundedMinutes);
		if (classification == Classification.BEHIND_PLAN && rounded.equals(ON_SCHEDULE_LIMIT)
				&& absolute.compareTo(ON_SCHEDULE_LIMIT) > 0) {
			return "just over 2h";
		}
		if (classification == Classification.ON_SCHEDULE && rounded.compareTo(ON_SCHEDULE_LIMIT) >= 0
				&& absolute.compareTo(ON_SCHEDULE_LIMIT) < 0) {
			return formatExact(absolute);
		}
		return formatExact(rounded);
	}

	private static String formatExact(Duration absolute) {
		long totalSeconds = absolute.getSeconds();
		long hours = totalSeconds / 3600;
		int minutes = (int) ((totalSeconds % 3600) / 60);
		int seconds = (int) (totalSeconds % 60);
		int nanos = absolute.getNano();
		StringBuilder text = new StringBuilder();
		if (hours > 0) {
			text.append(hours).append('h');
		}
		if (minutes > 0 || (hours > 0 && (seconds > 0 || nanos != 0))) {
			if (!text.isEmpty()) {
				text.append(' ');
			}
			text.append(minutes).append('m');
		}
		if (seconds > 0 || nanos != 0 || text.isEmpty()) {
			if (!text.isEmpty()) {
				text.append(' ');
			}
			text.append(seconds).append(fractional(nanos)).append('s');
		}
		return text.toString();
	}

	private static String fractional(int nanos) {
		if (nanos == 0) {
			return "";
		}
		String digits = "0".repeat(9 - Integer.toString(nanos).length()) + nanos;
		int end = digits.length();
		while (end > 0 && digits.charAt(end - 1) == '0') {
			end--;
		}
		return "." + digits.substring(0, end);
	}

	static double nauticalMiles(double latitude, double longitude, double portLatitude, double portLongitude) {
		double latitudeDelta = Math.toRadians(portLatitude - latitude);
		double longitudeDelta = Math.toRadians(portLongitude - longitude);
		double fromLatitude = Math.toRadians(latitude);
		double toLatitude = Math.toRadians(portLatitude);
		double haversine = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
				+ Math.cos(fromLatitude) * Math.cos(toLatitude) * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
		double arc = 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
		if (!Double.isFinite(arc)) {
			return Double.NaN;
		}
		return Math.toDegrees(arc) * 60;
	}

	private static Duration travelTime(double nauticalMiles, BigDecimal speedKnots) {
		BigDecimal seconds = BigDecimal.valueOf(nauticalMiles)
				.multiply(BigDecimal.valueOf(3600))
				.divide(speedKnots, 9, RoundingMode.HALF_UP);
		BigDecimal[] parts = seconds.divideAndRemainder(BigDecimal.ONE);
		long wholeSeconds = parts[0].longValueExact();
		int nanos = parts[1].movePointRight(9).intValueExact();
		return Duration.ofSeconds(wholeSeconds, nanos);
	}

	private static boolean unusable(BigDecimal latitude, BigDecimal longitude) {
		if (latitude == null || longitude == null || !finite(latitude) || !finite(longitude)) {
			return true;
		}
		return latitude.compareTo(BigDecimal.valueOf(-90)) < 0
				|| latitude.compareTo(BigDecimal.valueOf(90)) > 0
				|| longitude.compareTo(BigDecimal.valueOf(-180)) < 0
				|| longitude.compareTo(BigDecimal.valueOf(180)) > 0;
	}

	private static boolean unusableSpeed(BigDecimal speedKnots) {
		return speedKnots == null || !finite(speedKnots) || speedKnots.compareTo(MINIMUM_SPEED_KNOTS) < 0;
	}

	private static boolean finite(BigDecimal value) {
		return Double.isFinite(value.doubleValue());
	}

	private record SignedDifference(long seconds, int nanos) {
		private static SignedDifference from(Duration difference) {
			if (difference.isZero() || !difference.isNegative()) {
				return new SignedDifference(difference.getSeconds(), difference.getNano());
			}
			if (difference.getNano() == 0) {
				return new SignedDifference(difference.getSeconds(), 0);
			}
			return new SignedDifference(difference.getSeconds() + 1, difference.getNano() - 1_000_000_000);
		}
	}
}
