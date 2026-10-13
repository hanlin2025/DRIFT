package com.drift.backend.shipment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The route map's port coordinates. Lookup matches {@code locate} in the frontend:
 * trim, lowercase, drop a leading "port of", and keep the text before the first comma.
 */
public final class PortCatalog {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final Map<String, Coordinates> ports;

	private PortCatalog(Map<String, Coordinates> ports) {
		this.ports = ports;
	}

	public static PortCatalog load() {
		try (InputStream input = PortCatalog.class.getResourceAsStream("/ports/ports.json")) {
			if (input == null) {
				throw new IllegalStateException("The shared port catalog is not on the classpath");
			}
			return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException exception) {
			throw new IllegalStateException("The shared port catalog could not be read", exception);
		}
	}

	static PortCatalog parse(String json) throws IOException {
		JsonNode root = JSON.readTree(json);
		if (!root.isObject()) {
			throw new IllegalStateException("The shared port catalog must be an object of coordinates");
		}
		Map<String, Coordinates> ports = new LinkedHashMap<>();
		root.fields().forEachRemaining(entry -> ports.put(entry.getKey(), coordinates(entry.getValue())));
		return new PortCatalog(Map.copyOf(ports));
	}

	static PortCatalog of(Map<String, Coordinates> ports) {
		return new PortCatalog(Map.copyOf(ports));
	}

	public Optional<Coordinates> locate(String place) {
		if (place == null) {
			return Optional.empty();
		}
		String key = place.trim().toLowerCase(Locale.ROOT).replaceFirst("^port of\\s+", "");
		int comma = key.indexOf(',');
		if (comma >= 0) {
			key = key.substring(0, comma);
		}
		key = key.trim();
		if (key.isEmpty()) {
			return Optional.empty();
		}
		Coordinates coordinates = ports.get(key);
		if (coordinates == null || !coordinates.usable()) {
			return Optional.empty();
		}
		return Optional.of(coordinates);
	}

	private static Coordinates coordinates(JsonNode value) {
		if (!value.isArray() || value.size() != 2 || !value.get(0).isNumber() || !value.get(1).isNumber()) {
			return new Coordinates(Double.NaN, Double.NaN);
		}
		return new Coordinates(value.get(0).asDouble(), value.get(1).asDouble());
	}

	public record Coordinates(double longitude, double latitude) {
		public boolean usable() {
			return Double.isFinite(latitude) && Double.isFinite(longitude)
					&& latitude >= -90 && latitude <= 90
					&& longitude >= -180 && longitude <= 180;
		}
	}
}
