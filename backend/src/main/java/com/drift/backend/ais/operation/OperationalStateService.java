package com.drift.backend.ais.operation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

@Service
public class OperationalStateService {

	// Above typical AIS noise while a vessel is alongside or at anchor.
	private static final BigDecimal MOVEMENT_CONFLICT_KNOTS = new BigDecimal("1");
	// Distance from the port reference point that still counts as near that port.
	private static final double NEAR_PORT_NAUTICAL_MILES = 25;

	private static final String UNKNOWN = "Unknown";
	private static final List<KnownPort> PORTS = List.of(
			new KnownPort("Singapore", 1.264, 103.840),
			new KnownPort("Tanjung Pelepas", 1.362, 103.549));

	public OperationalState derive(Integer navigationalStatus, BigDecimal speedOverGroundKnots,
			BigDecimal latitude, BigDecimal longitude, String transshipmentPort) {
		String label = label(navigationalStatus);
		String description = describe(label, latitude, longitude, transshipmentPort);
		return new OperationalState(label, description, conflicts(navigationalStatus, speedOverGroundKnots));
	}

	private static String label(Integer navigationalStatus) {
		if (navigationalStatus == null) {
			return UNKNOWN;
		}
		return switch (navigationalStatus) {
			case 0 -> "Under way";
			case 1 -> "At anchor";
			case 2 -> "Not under command";
			case 3 -> "Restricted manoeuvrability";
			case 4 -> "Constrained by draught";
			case 5 -> "Moored";
			case 6 -> "Aground";
			case 7 -> "Engaged in fishing";
			case 8 -> "Under way sailing";
			case 11 -> "Towing astern";
			case 12 -> "Towing alongside";
			default -> UNKNOWN;
		};
	}

	private static boolean conflicts(Integer navigationalStatus, BigDecimal speedOverGroundKnots) {
		if (speedOverGroundKnots == null || !reportedStopped(navigationalStatus)) {
			return false;
		}
		return speedOverGroundKnots.compareTo(MOVEMENT_CONFLICT_KNOTS) > 0;
	}

	private static boolean reportedStopped(Integer navigationalStatus) {
		return navigationalStatus != null
				&& (navigationalStatus == 1 || navigationalStatus == 5 || navigationalStatus == 6);
	}

	private static String describe(String label, BigDecimal latitude, BigDecimal longitude, String transshipmentPort) {
		KnownPort port = match(transshipmentPort);
		if (port == null || !usable(latitude, longitude)) {
			return label;
		}
		double distance = nauticalMiles(latitude.doubleValue(), longitude.doubleValue(), port.latitude(),
				port.longitude());
		if (distance <= NEAR_PORT_NAUTICAL_MILES) {
			return label + " near " + port.name();
		}
		return label + ", far from port";
	}

	private static KnownPort match(String transshipmentPort) {
		if (transshipmentPort == null || transshipmentPort.isBlank()) {
			return null;
		}
		String normalized = transshipmentPort.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
		KnownPort match = null;
		for (KnownPort port : PORTS) {
			if (containsPhrase(normalized, port.name().toLowerCase(Locale.ROOT))
					&& (match == null || port.name().length() > match.name().length())) {
				match = port;
			}
		}
		return match;
	}

	private static boolean containsPhrase(String haystack, String phrase) {
		return haystack.equals(phrase)
				|| haystack.startsWith(phrase + " ")
				|| haystack.endsWith(" " + phrase)
				|| haystack.contains(" " + phrase + " ");
	}

	private static boolean usable(BigDecimal latitude, BigDecimal longitude) {
		if (latitude == null || longitude == null) {
			return false;
		}
		return latitude.compareTo(BigDecimal.valueOf(-90)) >= 0
				&& latitude.compareTo(BigDecimal.valueOf(90)) <= 0
				&& longitude.compareTo(BigDecimal.valueOf(-180)) >= 0
				&& longitude.compareTo(BigDecimal.valueOf(180)) <= 0;
	}

	private static double nauticalMiles(double latitude, double longitude, double portLatitude, double portLongitude) {
		double east = (longitude - portLongitude) * Math.cos(Math.toRadians((latitude + portLatitude) / 2));
		double north = latitude - portLatitude;
		return 60 * Math.hypot(east, north);
	}

	private record KnownPort(String name, double latitude, double longitude) {
	}
}
