package com.drift.backend.shipment;

import org.springframework.data.domain.Sort;

/** A whitelisted shipment-list sort. Equal values are ordered by id ascending. */
public record ShipmentSort(Field field, Direction direction) {

	public static final ShipmentSort CREATED_DATE_DESCENDING = new ShipmentSort(Field.CREATED_AT, Direction.DESC);

	public ShipmentSort {
		if (field == null || direction == null) {
			throw new IllegalArgumentException("Sort field and direction are required.");
		}
	}

	public Sort toSort() {
		return Sort.by(direction.springDirection(), field.property()).and(Sort.by(Sort.Direction.ASC, "id"));
	}

	public enum Field {
		PLANNED_ARRIVAL("plannedMotherArrivalAt"),
		REFERENCE("shipmentReference"),
		CREATED_AT("createdAt");

		private final String property;

		Field(String property) {
			this.property = property;
		}

		public String property() {
			return property;
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
