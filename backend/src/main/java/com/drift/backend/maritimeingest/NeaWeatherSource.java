package com.drift.backend.maritimeingest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.drift.backend.maritimechange.MaritimeSnapshot;
import com.drift.backend.shipment.PortCatalog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NEA two-hour forecasts, rainfall, and wind speed from data.gov.sg, for areas and stations near a listed port.
 * A forecast applies for its stated valid period. A reading applies for {@link #READING_PERIOD} after it was taken.
 * Rainfall is published per five minutes and is stored as millimetres per hour.
 */
@Component
class NeaWeatherSource implements MaritimeSnapshotSource {

	static final String FORECAST = "nea:forecast:";
	static final String RAINFALL = "nea:rainfall:";
	static final String WIND = "nea:wind:";
	static final Duration READING_PERIOD = Duration.ofMinutes(30);
	private static final String NEA_WEATHER = "NEA_WEATHER";
	private static final String LIVE = "LIVE";
	private static final double EARTH_RADIUS_KM = 6371.0;
	private static final ObjectMapper JSON = new ObjectMapper();

	private final MaritimeIngestProperties.Nea properties;
	private final MaritimeHttp http;
	private final PortCatalog ports;

	@Autowired
	NeaWeatherSource(MaritimeIngestProperties properties, MaritimeHttp http) {
		this(properties.nea(), http, PortCatalog.load());
	}

	NeaWeatherSource(MaritimeIngestProperties.Nea properties, MaritimeHttp http, PortCatalog ports) {
		this.properties = properties;
		this.http = http;
		this.ports = ports;
	}

	@Override
	public String name() {
		return "NEA weather";
	}

	@Override
	public boolean enabled() {
		return properties.enabled() && properties.baseUri() != null;
	}

	@Override
	public List<MaritimeSnapshot> fetch(Instant retrievedAt) {
		List<PortCatalog.Coordinates> near = properties.ports().stream()
				.map(ports::locate)
				.flatMap(Optional::stream)
				.toList();
		if (near.isEmpty()) {
			throw new MaritimeSourceException("No configured NEA port is on the route map");
		}
		Map<String, String> headers = properties.apiKey() == null || properties.apiKey().isBlank()
				? Map.of()
				: Map.of("x-api-key", properties.apiKey());
		List<MaritimeSnapshot> snapshots = new ArrayList<>();
		snapshots.addAll(forecasts(read("two-hr-forecast", headers), near, properties.portRadiusKm(), retrievedAt,
				endpoint("two-hr-forecast")));
		snapshots.addAll(readings(read("rainfall", headers), RAINFALL, near, properties.portRadiusKm(), retrievedAt,
				endpoint("rainfall")));
		snapshots.addAll(readings(read("wind-speed", headers), WIND, near, properties.portRadiusKm(), retrievedAt,
				endpoint("wind-speed")));
		return snapshots;
	}

	private JsonNode read(String dataset, Map<String, String> headers) {
		JsonNode body = JSON.readTree(http.get(endpoint(dataset), headers));
		JsonNode code = body.get("code");
		if (code != null && !code.isNull() && code.asInt(-1) != 0) {
			throw new MaritimeSourceException("NEA " + dataset + " returned code " + code.asString());
		}
		return body.path("data");
	}

	private URI endpoint(String dataset) {
		String base = properties.baseUri().toString();
		return URI.create(base.endsWith("/") ? base + dataset : base + "/" + dataset);
	}

	static List<MaritimeSnapshot> forecasts(JsonNode data, List<PortCatalog.Coordinates> near, double radiusKm,
			Instant retrievedAt, URI endpoint) {
		Map<String, JsonNode> areas = new HashMap<>();
		for (JsonNode area : data.path("area_metadata")) {
			JsonNode location = area.has("label_location") ? area.get("label_location") : area.path("labelLocation");
			areas.put(area.path("name").asString(""), location);
		}
		List<MaritimeSnapshot> snapshots = new ArrayList<>();
		for (JsonNode item : data.path("items")) {
			Instant start = SourceTimes.parse(item.path("valid_period").path("start").asString(null));
			Instant end = SourceTimes.parse(item.path("valid_period").path("end").asString(null));
			String issued = item.path("update_timestamp").asString(null);
			if (start == null || end == null) {
				continue;
			}
			for (JsonNode forecast : item.path("forecasts")) {
				String area = forecast.path("area").asString("").trim();
				String text = forecast.path("forecast").asString("").trim();
				JsonNode location = areas.get(area);
				if (area.isEmpty() || text.isEmpty() || location == null || !nearPort(location, near, radiusKm)) {
					continue;
				}
				snapshots.add(snapshot(FORECAST + area, endpoint, issued, retrievedAt, area, start, end, text));
			}
		}
		return snapshots;
	}

	static List<MaritimeSnapshot> readings(JsonNode data, String prefix, List<PortCatalog.Coordinates> near,
			double radiusKm, Instant retrievedAt, URI endpoint) {
		Map<String, JsonNode> stations = new HashMap<>();
		for (JsonNode station : data.path("stations")) {
			stations.put(station.path("id").asString(""), station);
		}
		List<MaritimeSnapshot> snapshots = new ArrayList<>();
		for (JsonNode reading : data.path("readings")) {
			String taken = reading.path("timestamp").asString(null);
			Instant at = SourceTimes.parse(taken);
			if (at == null) {
				continue;
			}
			for (JsonNode value : reading.path("data")) {
				String id = value.path("stationId").asString("");
				JsonNode station = stations.get(id);
				if (station == null || !value.path("value").isNumber() || !nearPort(station.path("location"), near, radiusKm)) {
					continue;
				}
				String name = station.path("name").asString(id);
				snapshots.add(snapshot(prefix + id, endpoint, taken, retrievedAt, name, at, at.plus(READING_PERIOD),
						reading(prefix, value.path("value").decimalValue())));
			}
		}
		return snapshots;
	}

	/** "Rainfall 14.4 mm/h" from a five-minute total, or "Wind 24.1 knots". */
	static String reading(String prefix, BigDecimal value) {
		if (RAINFALL.equals(prefix)) {
			return "Rainfall " + value.multiply(BigDecimal.valueOf(12)).setScale(1, RoundingMode.HALF_UP).toPlainString()
					+ " mm/h";
		}
		return "Wind " + value.setScale(1, RoundingMode.HALF_UP).toPlainString() + " knots";
	}

	private static MaritimeSnapshot snapshot(String identity, URI endpoint, String sourceEventAt, Instant retrievedAt,
			String location, Instant from, Instant until, String value) {
		return new MaritimeSnapshot(NEA_WEATHER, identity, "NEA via data.gov.sg " + endpoint, sourceEventAt, null,
				retrievedAt.toString(), LIVE, null, null, location, from.toString(), until.toString(), null, value, null,
				null, null);
	}

	private static boolean nearPort(JsonNode location, List<PortCatalog.Coordinates> near, double radiusKm) {
		if (!location.path("latitude").isNumber() || !location.path("longitude").isNumber()) {
			return false;
		}
		double latitude = location.path("latitude").asDouble();
		double longitude = location.path("longitude").asDouble();
		return near.stream().anyMatch(port -> kilometres(latitude, longitude, port.latitude(), port.longitude()) <= radiusKm);
	}

	static double kilometres(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
				* Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
	}
}
