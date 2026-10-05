package com.drift.backend.ais;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import com.drift.backend.ais.position.AisPositionParser;
import com.drift.backend.ais.position.LatestAisPositions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AisStreamConnectionServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void subscriptionUsesTheConfiguredBoundingBoxAndPositionReportFilter() throws Exception {
		AisStreamProperties properties = new AisStreamProperties(true,
				URI.create("wss://stream.aisstream.io/v0/stream"), "test-api-key",
				1.20, 103.60, 1.50, 104.00);
		AisStreamConnectionService service = new AisStreamConnectionService(properties, objectMapper,
				new AisPositionParser(objectMapper), new LatestAisPositions());

		JsonNode subscription = objectMapper.readTree(service.subscriptionPayload());
		assertThat(subscription.path("APIKey").asText()).isEqualTo("test-api-key");
		assertThat(subscription.path("BoundingBoxes").get(0).get(0).get(0).asDouble()).isEqualTo(1.20);
		assertThat(subscription.path("BoundingBoxes").get(0).get(1).get(1).asDouble()).isEqualTo(104.00);
		assertThat(subscription.path("FilterMessageTypes").get(0).asText()).isEqualTo("PositionReport");
	}

	@Test
	void keepsTheLatestPositionAndIgnoresAnOversizedHeading() {
		AisStreamProperties properties = new AisStreamProperties(true,
				URI.create("wss://stream.aisstream.io/v0/stream"), "test-api-key",
				1.20, 103.60, 1.50, 104.00);
		LatestAisPositions positions = new LatestAisPositions();
		AisStreamConnectionService service = new AisStreamConnectionService(properties, objectMapper,
				new AisPositionParser(objectMapper), positions);

		service.ingest("""
				{
				  "MessageType": "PositionReport",
				  "MetaData": { "MMSI": 368207620, "ShipName": "Ever Steady", "Latitude": 1.3, "Longitude": 103.8 },
				  "Message": { "PositionReport": { "Sog": 12.4, "Timestamp": 60 } }
				}
				""");
		service.ingest("""
				{
				  "MessageType": "PositionReport",
				  "MetaData": { "MMSI": 368207620, "ShipName": "Ever Steady", "Latitude": 1.3, "Longitude": 103.8 },
				  "Message": { "PositionReport": { "TrueHeading": 2147483648 } }
				}
				""");

		assertThat(positions.findByVesselName("ever steady")).isPresent();
		assertThat(positions.findByVesselName("ever steady").orElseThrow().mmsi()).isEqualTo("368207620");
		assertThat(positions.findByVesselName("ever steady").orElseThrow().aisUtcSecond()).isNull();
		assertThat(positions.findByMmsi("368207620")).isPresent();
	}
}
