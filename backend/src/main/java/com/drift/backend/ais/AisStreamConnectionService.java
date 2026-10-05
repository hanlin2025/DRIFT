package com.drift.backend.ais;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionParser;
import com.drift.backend.ais.position.LatestAisPositions;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;

@ConditionalOnProperty(prefix = "app.ais", name = "enabled", havingValue = "true")
@Service
public class AisStreamConnectionService {

	private static final String POSITION_REPORT = "PositionReport";
	private static final Duration RECONNECT_DELAY = Duration.ofSeconds(10);

	private final AisStreamProperties properties;
	private final ObjectMapper objectMapper;
	private final AisPositionParser positionParser;
	private final LatestAisPositions latestPositions;
	private final HttpClient httpClient;
	private final ScheduledExecutorService reconnects;
	private final Duration reconnectDelay;
	private final AtomicBoolean firstPositionLogged = new AtomicBoolean();
	private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

	private WebSocket webSocket;
	private volatile boolean stopping;

	public AisStreamConnectionService(AisStreamProperties properties, ObjectMapper objectMapper,
			AisPositionParser positionParser, LatestAisPositions latestPositions) {
		this(properties, objectMapper, positionParser, latestPositions, HttpClient.newHttpClient(),
				Executors.newSingleThreadScheduledExecutor(), RECONNECT_DELAY);
	}

	AisStreamConnectionService(AisStreamProperties properties, ObjectMapper objectMapper,
			AisPositionParser positionParser, LatestAisPositions latestPositions, HttpClient httpClient,
			ScheduledExecutorService reconnects, Duration reconnectDelay) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.positionParser = positionParser;
		this.latestPositions = latestPositions;
		this.httpClient = httpClient;
		this.reconnects = reconnects;
		this.reconnectDelay = reconnectDelay;
	}

	@EventListener(ApplicationReadyEvent.class)
	void connect() {
		validateConfiguration();
		open();
	}

	private void open() {
		if (stopping) {
			return;
		}
		httpClient.newWebSocketBuilder()
				.buildAsync(properties.streamUri(), new AisStreamListener())
				.whenComplete((socket, error) -> {
					if (error != null) {
						System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.ERROR,
								"Unable to connect to the AIS stream.", error);
						reconnect();
					}
				});
	}

	private void reconnect() {
		if (stopping || !reconnectScheduled.compareAndSet(false, true)) {
			return;
		}
		System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.WARNING,
				"Reconnecting to the AIS stream in " + reconnectDelay.toSeconds() + " seconds.");
		reconnects.schedule(() -> {
			reconnectScheduled.set(false);
			open();
		}, reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
	}

	String subscriptionPayload() {
		ObjectNode subscription = objectMapper.createObjectNode();
		subscription.put("APIKey", properties.apiKey());
		ArrayNode boundingBoxes = subscription.putArray("BoundingBoxes");
		ArrayNode boundingBox = boundingBoxes.addArray();
		addCoordinate(boundingBox, properties.southwestLatitude(), properties.southwestLongitude());
		addCoordinate(boundingBox, properties.northeastLatitude(), properties.northeastLongitude());
		subscription.putArray("FilterMessageTypes").add(POSITION_REPORT);
		try {
			return objectMapper.writeValueAsString(subscription);
		} catch (JacksonException ex) {
			throw new IllegalStateException("Unable to create the AIS subscription.", ex);
		}
	}

	private void validateConfiguration() {
		if (properties.streamUri() == null || properties.apiKey() == null || properties.apiKey().isBlank()) {
			throw new IllegalStateException("AIS stream URI and API key must be configured when AIS ingestion is enabled.");
		}
		validateCoordinate(properties.southwestLatitude(), "southwest latitude", -90, 90);
		validateCoordinate(properties.northeastLatitude(), "northeast latitude", -90, 90);
		validateCoordinate(properties.southwestLongitude(), "southwest longitude", -180, 180);
		validateCoordinate(properties.northeastLongitude(), "northeast longitude", -180, 180);
		if (properties.southwestLatitude() > properties.northeastLatitude()
				|| properties.southwestLongitude() > properties.northeastLongitude()) {
			throw new IllegalStateException("The AIS bounding box southwest corner must be before the northeast corner.");
		}
	}

	private static void validateCoordinate(Double coordinate, String name, double minimum, double maximum) {
		if (coordinate == null || coordinate < minimum || coordinate > maximum) {
			throw new IllegalStateException("AIS " + name + " must be between " + minimum + " and " + maximum + ".");
		}
	}

	private static void addCoordinate(ArrayNode boundingBox, double latitude, double longitude) {
		boundingBox.addArray().add(latitude).add(longitude);
	}

	@PreDestroy
	synchronized void close() {
		stopping = true;
		reconnects.shutdownNow();
		WebSocket currentSocket = webSocket;
		webSocket = null;
		if (currentSocket != null) {
			currentSocket.sendClose(WebSocket.NORMAL_CLOSURE, "DRIFT service stopping");
		}
	}

	private synchronized boolean adopt(WebSocket socket) {
		if (stopping) {
			return false;
		}
		webSocket = socket;
		return true;
	}

	private synchronized boolean release(WebSocket socket) {
		if (stopping || webSocket != socket) {
			return false;
		}
		webSocket = null;
		return true;
	}

	private static void ignoreObsoleteSocket() {
		System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.DEBUG,
				"Ignoring a callback from an AIS stream that is no longer current.");
	}

	void ingest(String payload) {
		Optional<AisPosition> position;
		try {
			position = positionParser.parse(payload);
		} catch (RuntimeException ex) {
			System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.WARNING,
					"Ignoring an invalid AIS position report.", ex);
			return;
		}
		position.ifPresent(this::store);
	}

	private void store(AisPosition position) {
		try {
			latestPositions.record(position);
		} catch (RuntimeException ex) {
			System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.ERROR,
					"Unable to store the AIS position report for MMSI " + position.mmsi() + ".", ex);
			return;
		}
		if (firstPositionLogged.compareAndSet(false, true)) {
			System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.INFO,
					"Received the first AIS position report for MMSI " + position.mmsi() + ".");
		}
	}

	private void handleMessage(String payload) {
		ingest(payload);
	}

	private final class AisStreamListener implements WebSocket.Listener {

		private final ByteArrayOutputStream frame = new ByteArrayOutputStream();

		@Override
		public void onOpen(WebSocket socket) {
			if (!adopt(socket)) {
				socket.sendClose(WebSocket.NORMAL_CLOSURE, "DRIFT service stopping");
				return;
			}
			socket.sendText(subscriptionPayload(), true).whenComplete((ignored, error) -> {
				if (error != null) {
					System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.ERROR,
							"Unable to subscribe to the AIS stream.", error);
				}
			});
			socket.request(1);
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
			byte[] bytes = new byte[data.remaining()];
			data.get(bytes);
			frame.writeBytes(bytes);
			if (last) {
				handleMessage(frame.toString(StandardCharsets.UTF_8));
				frame.reset();
			}
			socket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
			if (!release(socket)) {
				ignoreObsoleteSocket();
				return CompletableFuture.completedFuture(null);
			}
			System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.WARNING,
					"AIS stream closed with status " + statusCode + ": " + reason);
			reconnect();
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket socket, Throwable error) {
			if (!release(socket)) {
				ignoreObsoleteSocket();
				return;
			}
			System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.ERROR,
					"AIS stream error.", error);
			reconnect();
		}
	}
}
