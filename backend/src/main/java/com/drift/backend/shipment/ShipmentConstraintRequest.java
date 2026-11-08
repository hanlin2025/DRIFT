package com.drift.backend.shipment;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import com.drift.backend.shipment.exception.InvalidShipmentRequestException;

import tools.jackson.databind.JsonNode;

final class ShipmentConstraintRequest {

	static final String DEADLINE = "transshipmentArrivalDeadline";
	static final String EXPECTED_PORT = "expectedTransshipmentPort";
	static final String DEADLINE_REQUIRED = "Transshipment arrival deadline must be present. Send null to clear it.";
	static final String DEADLINE_INVALID = "The deadline must include a date, time, and timezone offset.";
	static final String PORT_REQUIRED = "Expected transshipment port is required.";
	static final String PORT_TOO_LONG = "Expected transshipment port must be at most 200 characters.";

	private final OffsetDateTime deadline;
	private final String expectedPort;

	private ShipmentConstraintRequest(OffsetDateTime deadline, String expectedPort) {
		this.deadline = deadline;
		this.expectedPort = expectedPort;
	}

	OffsetDateTime deadline() {
		return deadline;
	}

	String expectedPort() {
		return expectedPort;
	}

	static ShipmentConstraintRequest parse(JsonNode body) {
		if (body == null || body.isNull() || !body.isObject()) {
			throw new InvalidShipmentRequestException(Map.of());
		}
		Map<String, String> errors = new LinkedHashMap<>();
		OffsetDateTime deadline = deadline(body, errors);
		String expectedPort = expectedPort(body, errors);
		if (!errors.isEmpty()) {
			throw new InvalidShipmentRequestException(errors);
		}
		return new ShipmentConstraintRequest(deadline, expectedPort);
	}

	private static OffsetDateTime deadline(JsonNode body, Map<String, String> errors) {
		if (!body.has(DEADLINE)) {
			errors.put(DEADLINE, DEADLINE_REQUIRED);
			return null;
		}
		JsonNode value = body.get(DEADLINE);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			errors.put(DEADLINE, DEADLINE_INVALID);
			return null;
		}
		try {
			return OffsetDateTime.parse(value.asText()).toInstant().atOffset(ZoneOffset.UTC);
		} catch (DateTimeException exception) {
			errors.put(DEADLINE, DEADLINE_INVALID);
			return null;
		}
	}

	private static String expectedPort(JsonNode body, Map<String, String> errors) {
		if (!body.has(EXPECTED_PORT) || body.get(EXPECTED_PORT) == null || body.get(EXPECTED_PORT).isNull()
				|| !body.get(EXPECTED_PORT).isTextual()) {
			errors.put(EXPECTED_PORT, PORT_REQUIRED);
			return null;
		}
		String port = body.get(EXPECTED_PORT).asText().strip();
		if (port.isEmpty()) {
			errors.put(EXPECTED_PORT, PORT_REQUIRED);
			return null;
		}
		if (port.length() > 200) {
			errors.put(EXPECTED_PORT, PORT_TOO_LONG);
			return null;
		}
		return port;
	}
}
