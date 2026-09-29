package com.drift.backend.ais;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.drift.backend.ais.position.AisPositionParser;
import com.drift.backend.ais.position.exception.InvalidAisPositionMessageException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;

@ConditionalOnProperty(prefix = "app.ais", name = "enabled", havingValue = "true")
@Service
public class AisStreamConnectionService {

	private static final String POSITION_REPORT = "PositionReport";

	private final AisStreamProperties properties;
	private final ObjectMapper objectMapper;
	private final AisPositionParser positionParser;
	private final HttpClient httpClient;
	private final AtomicBoolean firstPositionLogged = new AtomicBoolean();

	private volatile WebSocket webSocket;

	public AisStreamConnectionService(AisStreamProperties properties, ObjectMapper objectMapper,
			AisPositionParser positionParser) {
		this(properties, objectMapper, positionParser, HttpClient.newHttpClient());
	}

	AisStreamConnectionService(AisStreamProperties properties, ObjectMapper objectMapper,
			AisPositionParser positionParser, HttpClient httpClient) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.positionParser = positionParser;
		this.httpClient = httpClient;
	}

	@EventListener(ApplicationReadyEvent.class)
	void connect() {
		validateConfiguration();
		httpClient.newWebSocketBuilder()
				.buildAsync(properties.streamUri(), new AisStreamListener())
				.whenComplete((socket, error) -> {
					if (error != null) {
						System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.ERROR,
								"Unable to connect to the AIS stream.", error);
					}
				});
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
	void close() {
		WebSocket currentSocket = webSocket;
		if (currentSocket != null) {
			currentSocket.sendClose(WebSocket.NORMAL_CLOSURE, "DRIFT service stopping");
		}
	}

	private void handleMessage(String payload) {
		try {
			positionParser.parse(payload).ifPresent(position -> {
				if (firstPositionLogged.compareAndSet(false, true)) {
					System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.INFO,
							"Received the first AIS position report for MMSI " + position.mmsi() + ".");
				}
			});
		} catch (InvalidAisPositionMessageException ex) {
			System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.WARNING,
					"Ignoring an invalid AIS position report.", ex);
		}
	}

	private final class AisStreamListener implements WebSocket.Listener {

		private final ByteArrayOutputStream frame = new ByteArrayOutputStream();

		@Override
		public void onOpen(WebSocket socket) {
			webSocket = socket;
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
			System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.WARNING,
					"AIS stream closed with status " + statusCode + ": " + reason);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket socket, Throwable error) {
			System.getLogger(AisStreamConnectionService.class.getName()).log(System.Logger.Level.ERROR,
					"AIS stream error.", error);
		}
	}
}
