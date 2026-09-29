package com.drift.backend.ais.position;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.drift.backend.ais.position.exception.InvalidAisPositionMessageException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AisPositionParser {

	private static final String POSITION_REPORT = "PositionReport";
	private static final BigDecimal UNAVAILABLE_SPEED_OVER_GROUND = new BigDecimal("102.3");
	private static final BigDecimal UNAVAILABLE_COURSE_OVER_GROUND = new BigDecimal("360");
	private static final int UNAVAILABLE_TRUE_HEADING = 511;

	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Autowired
	public AisPositionParser(ObjectMapper objectMapper) {
		this(objectMapper, Clock.systemUTC());
	}

	AisPositionParser(ObjectMapper objectMapper, Clock clock) {
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public Optional<AisPosition> parse(String rawMessage) {
		JsonNode envelope = readEnvelope(rawMessage);
		if (!POSITION_REPORT.equals(requiredText(envelope, "MessageType"))) {
			return Optional.empty();
		}

		JsonNode metadata = requiredObject(envelope, "MetaData");
		JsonNode positionReport = requiredObject(requiredObject(envelope, "Message"), POSITION_REPORT);
		return Optional.of(new AisPosition(
				parseMmsi(metadata.path("MMSI")),
				optionalText(metadata, "ShipName"),
				coordinate(metadata, positionReport, "Latitude", -90, 90),
				coordinate(metadata, positionReport, "Longitude", -180, 180),
				optionalDecimal(positionReport, "Sog", UNAVAILABLE_SPEED_OVER_GROUND),
				optionalDecimal(positionReport, "Cog", UNAVAILABLE_COURSE_OVER_GROUND),
				optionalInteger(positionReport, "TrueHeading", UNAVAILABLE_TRUE_HEADING),
				optionalInteger(positionReport, "NavigationalStatus"),
				optionalBoolean(positionReport, "Valid"),
				optionalInteger(positionReport, "Timestamp"),
				clock.instant()));
	}

	private JsonNode readEnvelope(String rawMessage) {
		if (rawMessage == null || rawMessage.isBlank()) {
			throw new InvalidAisPositionMessageException("AIS message is empty.");
		}
		try {
			JsonNode envelope = objectMapper.readTree(rawMessage);
			if (envelope == null || !envelope.isObject()) {
				throw new InvalidAisPositionMessageException("AIS message must be a JSON object.");
			}
			return envelope;
		} catch (JacksonException ex) {
			throw new InvalidAisPositionMessageException("AIS message is not valid JSON.", ex);
		}
	}

	private static String parseMmsi(JsonNode value) {
		if (!value.isIntegralNumber()) {
			throw new InvalidAisPositionMessageException("AIS position report must contain a numeric MMSI.");
		}
		long mmsi = value.asLong();
		if (mmsi < 100_000_000L || mmsi > 999_999_999L) {
			throw new InvalidAisPositionMessageException("AIS MMSI must contain nine digits.");
		}
		return Long.toString(mmsi);
	}

	private static BigDecimal coordinate(JsonNode metadata, JsonNode positionReport, String field,
			double minimum, double maximum) {
		JsonNode value = positionReport.hasNonNull(field) ? positionReport.path(field) : metadata.path(field);
		if (!value.isNumber()) {
			throw new InvalidAisPositionMessageException("AIS position report must contain a numeric " + field + ".");
		}
		BigDecimal coordinate = value.decimalValue();
		if (coordinate.compareTo(BigDecimal.valueOf(minimum)) < 0
				|| coordinate.compareTo(BigDecimal.valueOf(maximum)) > 0) {
			throw new InvalidAisPositionMessageException("AIS " + field + " is outside its valid range.");
		}
		return coordinate;
	}

	private static BigDecimal optionalDecimal(JsonNode object, String field, BigDecimal unavailableValue) {
		JsonNode value = object.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (!value.isNumber()) {
			throw new InvalidAisPositionMessageException("AIS " + field + " must be numeric when present.");
		}
		BigDecimal decimal = value.decimalValue();
		return decimal.compareTo(unavailableValue) == 0 ? null : decimal;
	}

	private static Integer optionalInteger(JsonNode object, String field) {
		JsonNode value = object.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (!value.isIntegralNumber()) {
			throw new InvalidAisPositionMessageException("AIS " + field + " must be an integer when present.");
		}
		return value.asInt();
	}

	private static Integer optionalInteger(JsonNode object, String field, int unavailableValue) {
		JsonNode value = object.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (!value.isIntegralNumber()) {
			throw new InvalidAisPositionMessageException("AIS " + field + " must be an integer when present.");
		}
		int integer = value.asInt();
		return integer == unavailableValue ? null : integer;
	}

	private static Boolean optionalBoolean(JsonNode object, String field) {
		JsonNode value = object.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (!value.isBoolean()) {
			throw new InvalidAisPositionMessageException("AIS " + field + " must be boolean when present.");
		}
		return value.asBoolean();
	}

	private static String optionalText(JsonNode object, String field) {
		JsonNode value = object.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new InvalidAisPositionMessageException("AIS " + field + " must be text when present.");
		}
		String text = value.asText().strip();
		return text.isEmpty() ? null : text;
	}

	private static JsonNode requiredObject(JsonNode object, String field) {
		JsonNode value = object.path(field);
		if (!value.isObject()) {
			throw new InvalidAisPositionMessageException("AIS position report must contain an object named " + field + ".");
		}
		return value;
	}

	private static String requiredText(JsonNode object, String field) {
		JsonNode value = object.path(field);
		if (!value.isTextual() || value.asText().isBlank()) {
			throw new InvalidAisPositionMessageException("AIS message must contain a text " + field + ".");
		}
		return value.asText();
	}
}
