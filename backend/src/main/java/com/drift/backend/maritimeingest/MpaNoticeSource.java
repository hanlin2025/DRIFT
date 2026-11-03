package com.drift.backend.maritimeingest;

import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.drift.backend.maritimechange.MaritimeSnapshot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The newest public MPA port marine notices. A notice applies from its release date. Its location is the
 * Singapore place its title names, followed by "Singapore", so it can match a shipment at that terminal
 * or at the port as a whole. A notice that names no place is about the port of Singapore.
 */
@Component
class MpaNoticeSource implements MaritimeSnapshotSource {

	static final String NOTICE = "mpa:notice:";
	private static final String MPA_OFFICIAL_NOTICE = "MPA_OFFICIAL_NOTICE";
	private static final String LIVE = "LIVE";
	private static final String SINGAPORE = "Singapore";
	private static final List<String> PLACES = List.of(
			"Tuas",
			"Pasir Panjang",
			"Keppel",
			"Brani",
			"Tanjong Pagar",
			"Jurong",
			"Sembawang",
			"Western Anchorage",
			"Eastern Anchorage",
			"Sinki",
			"Temasek Fairway");
	private static final ObjectMapper JSON = new ObjectMapper();

	private final MaritimeIngestProperties.MpaNotices properties;
	private final MaritimeHttp http;

	MpaNoticeSource(MaritimeIngestProperties properties, MaritimeHttp http) {
		this.properties = properties.mpaNotices();
		this.http = http;
	}

	@Override
	public String name() {
		return "MPA port marine notices";
	}

	@Override
	public boolean enabled() {
		return properties.enabled() && properties.listingUri() != null && properties.category() != null;
	}

	@Override
	public List<MaritimeSnapshot> fetch(Instant retrievedAt) {
		URI uri = UriComponentsBuilder.fromUri(properties.listingUri())
				.queryParam("type", properties.category())
				.queryParam("year", ZonedDateTime.ofInstant(retrievedAt, SourceTimes.SINGAPORE).getYear())
				.queryParam("limit", properties.limit() < 1 ? 20 : properties.limit())
				.queryParam("page", 1)
				.build()
				.toUri();
		return notices(JSON.readTree(http.get(uri, Map.of())), retrievedAt, uri);
	}

	static List<MaritimeSnapshot> notices(JsonNode body, Instant retrievedAt, URI endpoint) {
		JsonNode data = body.path("data");
		if (!data.isArray()) {
			throw new MaritimeSourceException("MPA notice listing has no data array");
		}
		List<MaritimeSnapshot> snapshots = new ArrayList<>();
		for (JsonNode item : data) {
			String id = item.path("id").asString("").trim();
			String title = item.path("title").asString("").trim();
			String released = item.path("release_date").asString(null);
			Instant from = SourceTimes.parse(released);
			if (id.isEmpty() || title.isEmpty() || from == null) {
				continue;
			}
			String status = item.path("status").asString("").trim();
			String value = status.isEmpty() ? title : title + " [" + status + "]";
			snapshots.add(new MaritimeSnapshot(MPA_OFFICIAL_NOTICE, NOTICE + id, "MPA official notice listing " + endpoint,
					released, null, retrievedAt.toString(), LIVE, null, null, place(title), from.toString(), null, null,
					value, null, null, null));
		}
		return snapshots;
	}

	static String place(String title) {
		String lower = title.toLowerCase(Locale.ROOT);
		for (String place : PLACES) {
			if (lower.contains(place.toLowerCase(Locale.ROOT))) {
				return place + ", " + SINGAPORE;
			}
		}
		return SINGAPORE;
	}
}
