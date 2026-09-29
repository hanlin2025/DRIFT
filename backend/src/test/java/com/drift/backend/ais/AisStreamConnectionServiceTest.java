package com.drift.backend.ais;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import com.drift.backend.ais.position.AisPositionParser;

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
				new AisPositionParser(objectMapper));

		JsonNode subscription = objectMapper.readTree(service.subscriptionPayload());
		assertThat(subscription.path("APIKey").asText()).isEqualTo("test-api-key");
		assertThat(subscription.path("BoundingBoxes").get(0).get(0).get(0).asDouble()).isEqualTo(1.20);
		assertThat(subscription.path("BoundingBoxes").get(0).get(1).get(1).asDouble()).isEqualTo(104.00);
		assertThat(subscription.path("FilterMessageTypes").get(0).asText()).isEqualTo("PositionReport");
	}
}
