package com.drift.backend.ais;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

	@Test
	void subscribesWhenTheStreamOpensAndKeepsReadingAfterAnInvalidMessage() {
		VesselObservationRepository observations = mock(VesselObservationRepository.class);
		WebSocket.Builder builder = webSocketBuilder();
		AisStreamConnectionService service = connectingService(observations, builder);
		WebSocket socket = mock(WebSocket.class);
		when(socket.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));

		service.connect();
		WebSocket.Listener listener = listener(builder);
		listener.onOpen(socket);
		listener.onBinary(socket, utf8("not-json"), true);
		listener.onBinary(socket, utf8(POSITION_REPORT.substring(0, 20)), false);
		listener.onBinary(socket, utf8(POSITION_REPORT.substring(20)), true);

		verify(socket).sendText(service.subscriptionPayload(), true);
		verify(socket, times(4)).request(1);
		verify(observations, times(1)).save(any(VesselObservation.class));
		service.close();
	}

	@Test
	void reconnectsAfterAFailedConnectionAttempt() {
		WebSocket.Builder builder = mock(WebSocket.Builder.class);
		when(builder.buildAsync(any(), any()))
				.thenReturn(CompletableFuture.failedFuture(new ConnectException("AIS stream unavailable")))
				.thenReturn(new CompletableFuture<>());
		AisStreamConnectionService service = connectingService(mock(VesselObservationRepository.class), builder);

		service.connect();

		verify(builder, timeout(1000).times(2)).buildAsync(eq(PROPERTIES.streamUri()), any());
		service.close();
	}

	@Test
	void reconnectsWhenTheStreamClosesOrFails() {
		WebSocket.Builder builder = webSocketBuilder();
		AisStreamConnectionService service = connectingService(mock(VesselObservationRepository.class), builder);

		service.connect();
		WebSocket first = openSocket();
		listener(builder).onOpen(first);
		latestListener(builder).onClose(first, WebSocket.NORMAL_CLOSURE, "server restarting");
		verify(builder, timeout(1000).times(2)).buildAsync(any(), any());
		WebSocket second = openSocket();
		WebSocket.Listener replacement = latestListener(builder);
		replacement.onOpen(second);
		replacement.onError(second, new IOException("connection reset"));
		verify(builder, timeout(1000).times(3)).buildAsync(any(), any());
		service.close();
	}

	@Test
	void doesNotReconnectAfterTheServiceStops() {
		WebSocket.Builder builder = webSocketBuilder();
		AisStreamConnectionService service = connectingService(mock(VesselObservationRepository.class), builder);
		WebSocket socket = openSocket();

		service.connect();
		WebSocket.Listener listener = listener(builder);
		listener.onOpen(socket);
		service.close();
		listener.onClose(socket, WebSocket.NORMAL_CLOSURE, "DRIFT service stopping");

		verify(socket).sendClose(eq(WebSocket.NORMAL_CLOSURE), any());
		verify(builder, after(200).times(1)).buildAsync(any(), any());
	}

	@Test
	void ignoresAnObsoleteCallbackBeforeTheRetryRuns() {
		WebSocket.Builder builder = webSocketBuilder();
		ScheduledExecutorService reconnects = mock(ScheduledExecutorService.class);
		AisStreamConnectionService service = controlledService(builder, reconnects);
		WebSocket first = openSocket();

		service.connect();
		WebSocket.Listener firstListener = listener(builder);
		firstListener.onOpen(first);
		firstListener.onError(first, new IOException("connection reset"));
		firstListener.onClose(first, 1006, "connection reset");
		runScheduledRetry(reconnects);

		verify(reconnects, times(1)).schedule(any(Runnable.class), anyLong(), any());
		verify(builder, times(2)).buildAsync(any(), any());
		service.close();
	}

	@Test
	void ignoresAnObsoleteCallbackWhileTheReplacementIsOpening() {
		WebSocket.Builder builder = webSocketBuilder();
		ScheduledExecutorService reconnects = mock(ScheduledExecutorService.class);
		AisStreamConnectionService service = controlledService(builder, reconnects);
		WebSocket first = openSocket();

		service.connect();
		WebSocket.Listener firstListener = listener(builder);
		firstListener.onOpen(first);
		firstListener.onError(first, new IOException("connection reset"));
		runScheduledRetry(reconnects);
		firstListener.onClose(first, 1006, "connection reset");

		verify(reconnects, times(1)).schedule(any(Runnable.class), anyLong(), any());
		verify(builder, times(2)).buildAsync(any(), any());
		service.close();
	}

	@Test
	void ignoresAnObsoleteCallbackAfterTheReplacementSubscribes() {
		WebSocket.Builder builder = webSocketBuilder();
		ScheduledExecutorService reconnects = mock(ScheduledExecutorService.class);
		AisStreamConnectionService service = controlledService(builder, reconnects);
		WebSocket first = openSocket();
		WebSocket second = openSocket();

		service.connect();
		WebSocket.Listener firstListener = listener(builder);
		firstListener.onOpen(first);
		firstListener.onError(first, new IOException("connection reset"));
		runScheduledRetry(reconnects);
		latestListener(builder).onOpen(second);
		firstListener.onClose(first, 1006, "connection reset");
		firstListener.onError(first, new IOException("connection reset"));

		verify(second).sendText(service.subscriptionPayload(), true);
		verify(reconnects, times(1)).schedule(any(Runnable.class), anyLong(), any());
		verify(builder, times(2)).buildAsync(any(), any());
		verify(second, never()).sendClose(anyInt(), any());
		service.close();
		verify(second, times(1)).sendClose(eq(WebSocket.NORMAL_CLOSURE), any());
	}

	@Test
	void closesASocketThatOpensAfterTheServiceStops() {
		WebSocket.Builder builder = webSocketBuilder();
		AisStreamConnectionService service = controlledService(builder, mock(ScheduledExecutorService.class));
		WebSocket socket = openSocket();

		service.connect();
		WebSocket.Listener listener = listener(builder);
		service.close();
		listener.onOpen(socket);

		verify(socket).sendClose(eq(WebSocket.NORMAL_CLOSURE), any());
		verify(socket, never()).sendText(any(), anyBoolean());
		verify(socket, never()).request(anyLong());
	}

	private static WebSocket.Builder webSocketBuilder() {
		WebSocket.Builder builder = mock(WebSocket.Builder.class);
		when(builder.buildAsync(any(), any())).thenAnswer(invocation -> new CompletableFuture<WebSocket>());
		return builder;
	}

	private static WebSocket.Listener listener(WebSocket.Builder builder) {
		ArgumentCaptor<WebSocket.Listener> listener = ArgumentCaptor.forClass(WebSocket.Listener.class);
		verify(builder).buildAsync(eq(PROPERTIES.streamUri()), listener.capture());
		return listener.getValue();
	}

	private static WebSocket.Listener latestListener(WebSocket.Builder builder) {
		ArgumentCaptor<WebSocket.Listener> listener = ArgumentCaptor.forClass(WebSocket.Listener.class);
		verify(builder, atLeastOnce()).buildAsync(eq(PROPERTIES.streamUri()), listener.capture());
		return listener.getValue();
	}

	private static WebSocket openSocket() {
		WebSocket socket = mock(WebSocket.class);
		when(socket.sendText(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));
		return socket;
	}

	private static void runScheduledRetry(ScheduledExecutorService reconnects) {
		ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
		verify(reconnects).schedule(retry.capture(), anyLong(), any());
		retry.getValue().run();
	}

	private static ByteBuffer utf8(String text) {
		return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
	}

	private AisStreamConnectionService connectingService(VesselObservationRepository observations,
			WebSocket.Builder builder) {
		HttpClient httpClient = mock(HttpClient.class);
		when(httpClient.newWebSocketBuilder()).thenReturn(builder);
		return new AisStreamConnectionService(PROPERTIES, objectMapper, new AisPositionParser(objectMapper),
				new LatestAisPositions(observations), httpClient, Executors.newSingleThreadScheduledExecutor(),
				Duration.ZERO);
	}

	private AisStreamConnectionService controlledService(WebSocket.Builder builder,
			ScheduledExecutorService reconnects) {
		HttpClient httpClient = mock(HttpClient.class);
		when(httpClient.newWebSocketBuilder()).thenReturn(builder);
		return new AisStreamConnectionService(PROPERTIES, objectMapper, new AisPositionParser(objectMapper),
				new LatestAisPositions(mock(VesselObservationRepository.class)), httpClient, reconnects,
				Duration.ZERO);
	}

	private AisStreamConnectionService service(VesselObservationRepository observations) {
		return new AisStreamConnectionService(PROPERTIES, objectMapper, new AisPositionParser(objectMapper),
				new LatestAisPositions(observations));
	}
}
