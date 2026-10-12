package com.drift.backend.shipment;

import java.util.Locale;

/** Compares a stored vessel name with the name on a live AIS position. */
final class VesselNames {

	static final String MOTHER_MISMATCH = "The AIS vessel name does not match the stored mother vessel.";
	static final String FEEDER_MISMATCH = "The AIS vessel name does not match the stored feeder vessel.";

	private VesselNames() {
	}

	static String warning(String storedName, VesselPosition position, String message) {
		if (position == null || position.vesselName() == null || position.vesselName().isBlank()) {
			return null;
		}
		if (storedName != null && normalize(storedName).equals(normalize(position.vesselName()))) {
			return null;
		}
		return message;
	}

	/**
	 * Same three steps as {@code LatestAisPositions}: trim, collapse whitespace, then upper-case.
	 * The MV / M/V prefix is left in place. Lookup may ignore that prefix; this comparison does not.
	 */
	static String normalize(String vesselName) {
		return vesselName.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}
}
