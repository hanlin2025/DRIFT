package com.drift.backend.shipment;

import java.util.LinkedHashMap;
import java.util.Map;

import com.drift.backend.shipment.exception.InvalidShipmentRequestException;

/** Decides whether a shipment list request stays a JSON array or asks for one page. */
final class ShipmentListQuery {

	static final String PAGE_ERROR = "Page must be a positive whole number";
	static final String SORT_ERROR = "Sort must be plannedArrival, reference, or createdAt";
	static final String DIRECTION_ERROR = "Direction must be asc or desc";
	static final String SIZE_ERROR = "Page size is fixed at 25";

	private final boolean legacy;
	private final int page;
	private final ShipmentSort sort;

	private ShipmentListQuery(boolean legacy, int page, ShipmentSort sort) {
		this.legacy = legacy;
		this.page = page;
		this.sort = sort;
	}

	static ShipmentListQuery parse(String page, String sort, String direction, String size) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (size != null) {
			errors.put("size", SIZE_ERROR);
		}
		Integer parsedPage = page == null ? null : parsePage(page, errors);
		ShipmentSort.Field field = sort == null ? null : parseField(sort, errors);
		ShipmentSort.Direction parsedDirection = direction == null ? null : parseDirection(direction, errors);
		if (!errors.isEmpty()) {
			throw new InvalidShipmentRequestException(errors);
		}
		if (page == null && sort == null && direction == null) {
			return new ShipmentListQuery(true, 1, ShipmentSort.CREATED_DATE_DESCENDING);
		}
		return new ShipmentListQuery(false, parsedPage == null ? 1 : parsedPage, new ShipmentSort(
				field == null ? ShipmentSort.Field.CREATED_AT : field,
				parsedDirection == null ? ShipmentSort.Direction.DESC : parsedDirection));
	}

	boolean legacy() {
		return legacy;
	}

	int page() {
		return page;
	}

	ShipmentSort sort() {
		return sort;
	}

	private static Integer parsePage(String raw, Map<String, String> errors) {
		String value = raw.strip();
		if (!value.matches("[0-9]+")) {
			errors.put("page", PAGE_ERROR);
			return null;
		}
		try {
			int parsed = Integer.parseInt(value);
			if (parsed < 1) {
				errors.put("page", PAGE_ERROR);
				return null;
			}
			return parsed;
		} catch (NumberFormatException exception) {
			errors.put("page", PAGE_ERROR);
			return null;
		}
	}

	private static ShipmentSort.Field parseField(String raw, Map<String, String> errors) {
		return switch (raw.strip()) {
			case "plannedArrival" -> ShipmentSort.Field.PLANNED_ARRIVAL;
			case "reference" -> ShipmentSort.Field.REFERENCE;
			case "createdAt" -> ShipmentSort.Field.CREATED_AT;
			default -> {
				errors.put("sort", SORT_ERROR);
				yield null;
			}
		};
	}

	private static ShipmentSort.Direction parseDirection(String raw, Map<String, String> errors) {
		return switch (raw.strip()) {
			case "asc" -> ShipmentSort.Direction.ASC;
			case "desc" -> ShipmentSort.Direction.DESC;
			default -> {
				errors.put("direction", DIRECTION_ERROR);
				yield null;
			}
		};
	}
}
