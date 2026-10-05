package com.drift.backend.ais.position;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.drift.backend.ais.position.exception.InvalidAisPositionMessageException;

import tools.jackson.databind.ObjectMapper;

class AisPositionParserTest {

	private static final Instant INGESTED_AT = Instant.parse("2026-09-29T05:00:00Z");
	private final AisPositionParser parser = new AisPositionParser(new ObjectMapper(),
			Clock.fixed(INGESTED_AT, ZoneOffset.UTC));

	@Test
	void parsesAValidPositionReport() {
		AisPosition position = parser.parse("""
				{
				  "MessageType": "PositionReport",
				  "MetaData": {
				    "MMSI": 368207620,
				    "ShipName": "EXAMPLE VESSEL",
				    "Latitude": 25.7617,
				    "Longitude": -80.1918
				  },
				  "Message": {
				    "PositionReport": {
				      "Sog": 12.4,
				      "Cog": 86.7,
				      "TrueHeading": 87,
				      "NavigationalStatus": 5,
				      "Valid": true,
				      "Timestamp": 42
				    }
				  }
				}
				""").orElseThrow();

		assertThat(position.mmsi()).isEqualTo("368207620");
		assertThat(position.vesselName()).isEqualTo("EXAMPLE VESSEL");
		assertThat(position.latitude()).isEqualByComparingTo("25.7617");
		assertThat(position.longitude()).isEqualByComparingTo("-80.1918");
		assertThat(position.speedOverGroundKnots()).isEqualByComparingTo("12.4");
		assertThat(position.courseOverGroundDegrees()).isEqualByComparingTo("86.7");
		assertThat(position.trueHeadingDegrees()).isEqualTo(87);
		assertThat(position.navigationalStatus()).isEqualTo(5);
		assertThat(position.positionValid()).isTrue();
		assertThat(position.aisUtcSecond()).isEqualTo(42);
		assertThat(position.ingestedAt()).isEqualTo(INGESTED_AT);
		assertThat(position.source()).isEqualTo(AisPositionSource.AIS_STREAM);
	}

	@Test
	void preservesMissingOptionalFieldsAndUsesMetadataCoordinatesAsFallback() {
		AisPosition position = parser.parse("""
				{
				  "MessageType": "PositionReport",
				  "MetaData": {
				    "MMSI": 368207620,
				    "Latitude": 1.3,
				    "Longitude": 103.8
				  },
				  "Message": { "PositionReport": {} }
				}
				""").orElseThrow();

		assertThat(position.vesselName()).isNull();
		assertThat(position.latitude()).isEqualByComparingTo(BigDecimal.valueOf(1.3));
		assertThat(position.longitude()).isEqualByComparingTo(BigDecimal.valueOf(103.8));
		assertThat(position.speedOverGroundKnots()).isNull();
		assertThat(position.courseOverGroundDegrees()).isNull();
		assertThat(position.trueHeadingDegrees()).isNull();
		assertThat(position.navigationalStatus()).isNull();
		assertThat(position.positionValid()).isNull();
		assertThat(position.aisUtcSecond()).isNull();
	}

	@Test
	void usesPositionReportCoordinatesWhenBothLocationsArePresent() {
		AisPosition position = parser.parse("""
				{
				  "MessageType": "PositionReport",
				  "MetaData": { "MMSI": 368207620, "Latitude": 25.7617, "Longitude": -80.1918 },
				  "Message": {
				    "PositionReport": { "Latitude": 1.3, "Longitude": 103.8 }
				  }
				}
				""").orElseThrow();

		assertThat(position.latitude()).isEqualByComparingTo("1.3");
		assertThat(position.longitude()).isEqualByComparingTo("103.8");
	}

	@Test
	void mapsUnavailableSpeedOverGroundToNull() {
		AisPosition position = parser.parse(positionReportWithMovement("\"Sog\": 102.3")).orElseThrow();

		assertThat(position.speedOverGroundKnots()).isNull();
	}

	@Test
	void mapsUnavailableCourseOverGroundToNull() {
		AisPosition position = parser.parse(positionReportWithMovement("\"Cog\": 360")).orElseThrow();

		assertThat(position.courseOverGroundDegrees()).isNull();
	}

	@Test
	void mapsUnavailableTrueHeadingToNull() {
		AisPosition position = parser.parse(positionReportWithMovement("\"TrueHeading\": 511")).orElseThrow();

		assertThat(position.trueHeadingDegrees()).isNull();
	}

	@Test
	void mapsUnavailableTimestampSentinelsToNull() {
		assertThat(second("60")).isNull();
		assertThat(second("61")).isNull();
		assertThat(second("62")).isNull();
		assertThat(second("63")).isNull();
		assertThat(second("42")).isEqualTo(42);
	}

	@Test
	void rejectsAnIntegerThatDoesNotFitInAnInt() {
		assertThatThrownBy(() -> parser.parse(positionReportWithMovement("\"TrueHeading\": 2147483648")))
				.isInstanceOf(InvalidAisPositionMessageException.class)
				.hasMessage("AIS TrueHeading must be an integer when present.");
	}

	@Test
	void rejectsATimestampOutsideTheAisRange() {
		assertThatThrownBy(() -> parser.parse(positionReportWithMovement("\"Timestamp\": 64")))
				.isInstanceOf(InvalidAisPositionMessageException.class)
				.hasMessage("AIS Timestamp is outside its valid range.");
	}

	private AisPosition secondPosition(String timestamp) {
		return parser.parse(positionReportWithMovement("\"Timestamp\": " + timestamp)).orElseThrow();
	}

	private Integer second(String timestamp) {
		return secondPosition(timestamp).aisUtcSecond();
	}

	@Test
	void ignoresMessagesThatAreNotPositionReports() {
		assertThat(parser.parse("""
				{"MessageType":"SubscriptionConfirmation","Message":{"CompressionEnabled":true}}
				""")).isEmpty();
	}

	@Test
	void rejectsMalformedJson() {
		assertThatThrownBy(() -> parser.parse("not-json"))
				.isInstanceOf(InvalidAisPositionMessageException.class)
				.hasMessage("AIS message is not valid JSON.");
	}

	@Test
	void rejectsPositionReportsWithoutCoordinates() {
		assertThatThrownBy(() -> parser.parse("""
				{
				  "MessageType":"PositionReport",
				  "MetaData":{"MMSI":368207620},
				  "Message":{"PositionReport":{}}
				}
				"""))
				.isInstanceOf(InvalidAisPositionMessageException.class)
				.hasMessage("AIS position report must contain a numeric Latitude.");
	}

	private static String positionReportWithMovement(String movementField) {
		return """
				{
				  "MessageType": "PositionReport",
				  "MetaData": { "MMSI": 368207620, "Latitude": 1.3, "Longitude": 103.8 },
				  "Message": { "PositionReport": { %s } }
				}
				""".formatted(movementField);
	}

}
