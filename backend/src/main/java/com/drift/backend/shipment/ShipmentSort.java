package com.drift.backend.shipment;

import java.util.Comparator;
import java.util.Map;

import org.springframework.data.domain.Sort;

import com.drift.backend.shipment.exception.InvalidShipmentRequestException;

/**
 * A whitelisted shipment-list sort. Equal values are ordered by id ascending.
 * RISK is calculated when shipments are read, not stored, so it sorts the mapped rows instead of the database query
 * and always puts the most urgent shipment first.
 */
public record ShipmentSort(Field field, Direction direction) {

	public static final ShipmentSort CREATED_DATE_DESCENDING = new ShipmentSort(Field.CREATED_AT, Direction.DESC);

	/** CRITICAL, HIGH, MEDIUM, LOW, then not assessed; ties go to the soonest planned feeder departure. */
	public static final ShipmentSort RISK_FIRST = new ShipmentSort(Field.RISK, Direction.DESC);

	private static final Comparator<ShipmentResponse> BY_ID = Comparator.comparing(ShipmentResponse::id);

	private static final Comparator<ShipmentResponse> BY_FEEDER_DEPARTURE = Comparator.comparing(
			ShipmentResponse::plannedFeederDepartureAt, Comparator.nullsLast(Comparator.naturalOrder()));

	private static final Comparator<ShipmentResponse> BY_RISK = Comparator
			.comparingInt((ShipmentResponse response) -> riskRank(response.risk()))
			.thenComparing(BY_FEEDER_DEPARTURE)
			.thenComparing(BY_ID);

	public ShipmentSort {
		if (field == null || direction == null) {
			throw new IllegalArgumentException("Sort field and direction are required.");
		}
		if (field.computed() && direction != Direction.DESC) {
			throw new IllegalArgumentException("The risk sort always puts the most urgent shipment first.");
		}
	}

	/** Reads the list endpoint's optional sort parameter. Blank keeps newest first. */
	public static ShipmentSort fromListParameter(String value) {
		String normalized = value == null ? "" : value.strip();
		if (normalized.isEmpty()) {
			return CREATED_DATE_DESCENDING;
		}
		if ("risk".equalsIgnoreCase(normalized)) {
			return RISK_FIRST;
		}
		throw new InvalidShipmentRequestException(Map.of("sort", "Sort must be risk"));
	}

	public boolean computed() {
		return field.computed();
	}

	public Sort toSort() {
		if (computed()) {
			throw new IllegalStateException(field + " is calculated on read and cannot be sorted in the database.");
		}
		return Sort.by(direction.springDirection(), field.property()).and(Sort.by(Sort.Direction.ASC, "id"));
	}

	/** Orders mapped rows for a calculated sort. */
	Comparator<ShipmentResponse> comparator() {
		return switch (field) {
			case RISK -> BY_RISK;
			default -> throw new IllegalStateException(field + " is sorted in the database.");
		};
	}

	private static int riskRank(ShipmentRisk risk) {
		return risk == null ? Integer.MAX_VALUE : -risk.level().ordinal();
	}

	public enum Field {
		PLANNED_ARRIVAL("plannedMotherArrivalAt"),
		REFERENCE("shipmentReference"),
		CREATED_AT("createdAt"),
		RISK(null);

		private final String property;

		Field(String property) {
			this.property = property;
		}

		public String property() {
			return property;
		}

		public boolean computed() {
			return property == null;
		}
	}

	public enum Direction {
		ASC,
		DESC;

		private Sort.Direction springDirection() {
			return this == ASC ? Sort.Direction.ASC : Sort.Direction.DESC;
		}
	}
}
