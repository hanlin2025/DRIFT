package com.drift.backend.maritimeingest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.drift.backend.maritimechange.CurrentMaritimeSignals;
import com.drift.backend.maritimechange.MaritimeSnapshot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MPA OCEANS-X expected arrivals at the port of Singapore, one snapshot per IMO. The value is the expected
 * arrival instant. When it differs from the stored head, the head's value becomes the previous value. When it is
 * unchanged, the stored previous value is kept, so an unchanged arrival does not record a new revision.
 * The effective period is {@link #SAME_CALL} either side of the expected arrival, so a planned arrival in that
 * period is treated as the same port call.
 */
@Component
class MpaExpectedArrivalSource implements MaritimeSnapshotSource {

	static final String DUE_TO_ARRIVE = "mpa:due-to-arrive:";
	static final Duration SAME_CALL = Duration.ofHours(72);
	private static final String MPA_ETA_REVISION = "MPA_ETA_REVISION";
	private static final String LIVE = "LIVE";
	private static final String LOCATION = "Singapore";
	private static final ObjectMapper JSON = new ObjectMapper();

	private final MaritimeIngestProperties.MpaOceansx properties;
	private final MaritimeHttp http;
	private final CurrentMaritimeSignals heads;

	MpaExpectedArrivalSource(MaritimeIngestProperties properties, MaritimeHttp http, CurrentMaritimeSignals heads) {
		this.properties = properties.mpaOceansx();
		this.http = http;
		this.heads = heads;
	}

	@Override
	public String name() {
		return "MPA expected arrivals";
	}

	@Override
	public boolean enabled() {
		return properties.configured();
	}

	@Override
	public List<MaritimeSnapshot> fetch(Instant retrievedAt) {
		LocalDate today = retrievedAt.atZone(SourceTimes.SINGAPORE).toLocalDate();
		int days = Math.max(1, properties.lookaheadDays() + 1);
		Map<String, Arrival> latest = new LinkedHashMap<>();
		for (int day = 0; day < days; day++) {
			URI uri = URI.create(properties.baseUri().replaceAll("/+$", "")
					+ properties.dueToArrivePath().replace("{date}", today.plusDays(day).toString()));
			for (Arrival arrival : arrivals(JSON.readTree(http.get(uri, Map.of("apikey", properties.apiKey()))))) {
				latest.put(arrival.imo(), arrival);
			}
		}
		List<MaritimeSnapshot> snapshots = new ArrayList<>();
		for (Arrival arrival : latest.values()) {
			String identity = DUE_TO_ARRIVE + arrival.imo();
			snapshots.add(snapshot(arrival, previous(heads.head(MPA_ETA_REVISION, identity), arrival.expected()),
					retrievedAt));
		}
		return snapshots;
	}

	record Arrival(String imo, String vesselName, String sourceValue, Instant expected) {
	}

	static List<Arrival> arrivals(JsonNode body) {
		if (!body.isArray()) {
			throw new MaritimeSourceException("OCEANS-X due-to-arrive response is not an array");
		}
		List<Arrival> arrivals = new ArrayList<>();
		for (JsonNode record : body) {
			JsonNode particulars = record.path("vesselParticulars");
			String imo = particulars.path("imoNumber").asString("").trim();
			String sourceValue = record.path("duetoArriveTime").asString(null);
			Instant expected = SourceTimes.parse(sourceValue);
			if (!imo.matches("\\d{7}") || expected == null) {
				continue;
			}
			arrivals.add(new Arrival(imo, particulars.path("vesselName").asString(""), sourceValue, expected));
		}
		return arrivals;
	}

	static String previous(Optional<CurrentMaritimeSignals.HeadValues> head, Instant expected) {
		if (head.isEmpty()) {
			return null;
		}
		String current = expected.toString();
		return current.equals(head.get().currentValue()) ? head.get().previousValue() : head.get().currentValue();
	}

	static MaritimeSnapshot snapshot(Arrival arrival, String previousValue, Instant retrievedAt) {
		return new MaritimeSnapshot(MPA_ETA_REVISION, DUE_TO_ARRIVE + arrival.imo(),
				"MPA OCEANS-X due to arrive" + (arrival.vesselName().isBlank() ? "" : " (" + arrival.vesselName() + ")"),
				arrival.sourceValue(), null, retrievedAt.toString(), LIVE, null, arrival.imo(), LOCATION,
				arrival.expected().minus(SAME_CALL).toString(), arrival.expected().plus(SAME_CALL).toString(),
				previousValue, arrival.expected().toString(), null, null, null);
	}
}
