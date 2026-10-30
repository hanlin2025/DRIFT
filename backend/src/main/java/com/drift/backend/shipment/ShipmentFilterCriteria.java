package com.drift.backend.shipment;

import java.time.LocalDate;

/** Normalized optional filters for the shipment list. */
public record ShipmentFilterCriteria(
		String reference,
		String vessel,
		String customer,
		LocalDate arrivalDate,
		ShipmentStatus status,
		boolean atRisk) {

	public ShipmentFilterCriteria {
		reference = optionalText(reference);
		vessel = optionalText(vessel);
		customer = optionalText(customer);
	}

	public static ShipmentFilterCriteria of(String reference, String vessel, String customer, LocalDate arrivalDate) {
		return of(reference, vessel, customer, arrivalDate, null);
	}

	public static ShipmentFilterCriteria of(String reference, String vessel, String customer, LocalDate arrivalDate,
			ShipmentStatus status) {
		return of(reference, vessel, customer, arrivalDate, status, null);
	}

	public static ShipmentFilterCriteria of(String reference, String vessel, String customer, LocalDate arrivalDate,
			ShipmentStatus status, Boolean atRisk) {
		return new ShipmentFilterCriteria(reference, vessel, customer, arrivalDate, status, Boolean.TRUE.equals(atRisk));
	}

	public static ShipmentFilterCriteria none() {
		return new ShipmentFilterCriteria(null, null, null, null, null, false);
	}

	/** HIGH and CRITICAL count as at risk. A shipment without a risk level is not at risk. */
	static boolean isAtRisk(ShipmentRisk risk) {
		return risk != null && (risk.level() == ShipmentRisk.Level.HIGH || risk.level() == ShipmentRisk.Level.CRITICAL);
	}

	private static String optionalText(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.strip();
		return normalized.isEmpty() ? null : normalized;
	}
}
