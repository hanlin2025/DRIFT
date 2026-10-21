package com.drift.backend.shipment;

import java.time.LocalDate;
import java.util.Map;

import com.drift.backend.shipment.exception.InvalidShipmentRequestException;

/** Normalized optional filters and ordering for the shipment list. */
public record ShipmentFilterCriteria(
		String reference,
		String vessel,
		String customer,
		LocalDate arrivalDate,
		ShipmentStatus status,
		Sort sort,
		boolean atRisk) {

	/** Supported list orderings. CREATED_AT is the default, newest first. */
	public enum Sort {
		CREATED_AT,
		RISK
	}

	public ShipmentFilterCriteria {
		reference = optionalText(reference);
		vessel = optionalText(vessel);
		customer = optionalText(customer);
		sort = sort == null ? Sort.CREATED_AT : sort;
	}

	public static ShipmentFilterCriteria of(String reference, String vessel, String customer, LocalDate arrivalDate) {
		return of(reference, vessel, customer, arrivalDate, null);
	}

	public static ShipmentFilterCriteria of(String reference, String vessel, String customer, LocalDate arrivalDate,
			ShipmentStatus status) {
		return new ShipmentFilterCriteria(reference, vessel, customer, arrivalDate, status, Sort.CREATED_AT, false);
	}

	public static ShipmentFilterCriteria of(String reference, String vessel, String customer, LocalDate arrivalDate,
			ShipmentStatus status, String sort, Boolean atRisk) {
		return new ShipmentFilterCriteria(reference, vessel, customer, arrivalDate, status, parseSort(sort),
				Boolean.TRUE.equals(atRisk));
	}

	public static ShipmentFilterCriteria none() {
		return new ShipmentFilterCriteria(null, null, null, null, null, Sort.CREATED_AT, false);
	}

	private static Sort parseSort(String value) {
		String normalized = optionalText(value);
		if (normalized == null) {
			return Sort.CREATED_AT;
		}
		if ("risk".equalsIgnoreCase(normalized)) {
			return Sort.RISK;
		}
		throw new InvalidShipmentRequestException(Map.of("sort", "Sort must be risk"));
	}

	private static String optionalText(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.strip();
		return normalized.isEmpty() ? null : normalized;
	}
}
