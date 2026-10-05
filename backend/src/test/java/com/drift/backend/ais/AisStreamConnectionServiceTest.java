package com.drift.backend.ais;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.drift.backend.ais.position.AisPositionParser;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.ais.position.VesselObservation;
import com.drift.backend.ais.position.VesselObservationRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AisStreamConnectionServiceTest {

	private static final AisStreamProperties PROPERTIES = new AisStreamProperties(true,
			URI.create("wss://stream.aisstream.io/v0/stream"), "test-api-key",
			1.20, 103.60, 1.50, 104.00);
	private static final String POSITION_REPORT = """
			{
			  "MessageType": "PositionReport",
			  "MetaData": { "MMSI": 368207620, "ShipName": "Ever Steady", "Latitude": 1.3, "Longitude": 103.8 },
			  "Message": { "PositionReport": {} }
			}
			""";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void subscriptionUsesTheConfiguredBoundingBoxAndPositionReportFilter() throws Exception {
		AisStreamConnectionService service = service(mock(VesselObservationRepository.class));

		JsonNode subscription = objectMapper.readTree(service.subscriptionPayload());
		assertThat(subscription.path("APIKey").asText()).isEqualTo("test-api-key");
		assertThat(subscription.path("BoundingBoxes").get(0).get(0).get(0).asDouble()).isEqualTo(1.20);
		assertThat(subscription.path("BoundingBoxes").get(0).get(1).get(1).asDouble()).isEqualTo(104.00);
		assertThat(subscription.path("FilterMessageTypes").get(0).asText()).isEqualTo("PositionReport");
	}

	@Test
	void storesAValidReportAndIgnoresAnOversizedHeading() {
		VesselObservationRepository observations = mock(VesselObservationRepository.class);
		AisStreamConnectionService service = service(observations);

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

		verify(observations, times(1)).save(any(VesselObservation.class));
	}

	@Test
	void keepsIngestingWhenAnObservationCannotBeStored() {
		VesselObservationRepository observations = mock(VesselObservationRepository.class);
		when(observations.save(any(VesselObservation.class)))
				.thenThrow(new DataAccessResourceFailureException("database unavailable"))
				.thenAnswer(invocation -> invocation.getArgument(0));
		AisStreamConnectionService service = service(observations);

		assertThatCode(() -> service.ingest(POSITION_REPORT)).doesNotThrowAnyException();
		service.ingest(POSITION_REPORT);

		verify(observations, times(2)).save(any(VesselObservation.class));
	}

	private AisStreamConnectionService service(VesselObservationRepository observations) {
		return new AisStreamConnectionService(PROPERTIES, objectMapper, new AisPositionParser(objectMapper),
				new LatestAisPositions(observations));
	}
}
