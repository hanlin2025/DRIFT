package com.drift.backend.ais;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionParser;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AisStreamIngestionIntegrationTests {

	@Autowired ObjectMapper objectMapper;
	@Autowired AisPositionParser positionParser;
	@Autowired LatestAisPositions latestPositions;
	@Autowired JdbcTemplate jdbc;

	private AisStreamConnectionService service;

	@BeforeEach
	void service() {
		AisStreamProperties properties = new AisStreamProperties(true,
				URI.create("wss://stream.aisstream.io/v0/stream"), "test-api-key",
				1.20, 103.60, 1.50, 104.00);
		service = new AisStreamConnectionService(properties, objectMapper, positionParser, latestPositions);
	}

	@Test
	void storesALivePositionReportAndReturnsItAsTheLatestObservation() {
		service.ingest(positionReport("1.264", "103.82",
				"\"Sog\": 12.4, \"Cog\": 175.0, \"TrueHeading\": 176, \"Timestamp\": 42"));
		service.ingest(positionReport("1.270", "103.83", "\"Sog\": 102.3, \"Cog\": 360, \"TrueHeading\": 511"));

		assertThat(countObservations()).isEqualTo(2);
		assertThat(jdbc.queryForList("SELECT DISTINCT source FROM vessel_observations WHERE mmsi = '563001234'",
				String.class)).containsExactly("AIS_STREAM");

		AisPosition latest = latestPositions.findByVesselName("Pacific Horizon").orElseThrow();
		assertThat(latest.mmsi()).isEqualTo("563001234");
		assertThat(latest.latitude()).isEqualByComparingTo("1.270");
		assertThat(latest.longitude()).isEqualByComparingTo("103.83");
		assertThat(latest.speedOverGroundKnots()).isNull();
		assertThat(latest.courseOverGroundDegrees()).isNull();
		assertThat(latest.trueHeadingDegrees()).isNull();
		assertThat(latest.ingestedAt()).isNotNull();
		assertThat(latest.source()).isEqualTo(AisPositionSource.AIS_STREAM);
		assertThat(latestPositions.findByMmsi("563001234").orElseThrow()).isEqualTo(latest);
	}

	@Test
	void ignoresUnusableMessagesWithoutStoringThemOrStoppingIngestion() {
		assertThatCode(() -> {
			service.ingest(null);
			service.ingest("not-json");
			service.ingest("[]");
			service.ingest("""
					{"MessageType":"SubscriptionConfirmation","Message":{"CompressionEnabled":true}}
					""");
			service.ingest("""
					{"MessageType":"PositionReport","MetaData":{"MMSI":563001234},"Message":{"PositionReport":{}}}
					""");
			service.ingest("""
					{"MessageType":"PositionReport","MetaData":{"MMSI":12345,"Latitude":1.3,"Longitude":103.8},
					 "Message":{"PositionReport":{}}}
					""");
			service.ingest(positionReport("91", "103.82", ""));
			service.ingest(positionReport("1.264", "103.82", "\"Timestamp\": 64"));
		}).doesNotThrowAnyException();
		assertThat(countObservations()).isZero();

		service.ingest(positionReport("1.264", "103.82", ""));
		assertThat(countObservations()).isEqualTo(1);
	}

	private int countObservations() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM vessel_observations WHERE mmsi = '563001234'", Integer.class);
	}

	private static String positionReport(String latitude, String longitude, String movementFields) {
		return """
				{
				  "MessageType": "PositionReport",
				  "MetaData": { "MMSI": 563001234, "ShipName": "PACIFIC HORIZON ", "Latitude": %s, "Longitude": %s },
				  "Message": { "PositionReport": { %s } }
				}
				""".formatted(latitude, longitude, movementFields);
	}
}
