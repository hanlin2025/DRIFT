package com.drift.backend.shipment;

import java.time.LocalDate;

/** Normalized optional filters for the shipment list. */
public record ShipmentFilterCriteria(
		String reference,
		String vessel,
		String customer,
		LocalDate arrivalDate,
		ShipmentStatus status) {

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
		return new ShipmentFilterCriteria(reference, vessel, customer, arrivalDate, status);
	}

	public static ShipmentFilterCriteria none() {
		return new ShipmentFilterCriteria(null, null, null, null, null);
	}

	private static String optionalText(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.strip();
		return normalized.isEmpty() ? null : normalized;
	}
}
