package com.drift.backend.maritimeingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimechange.CurrentMaritimeSignals;
import com.drift.backend.maritimechange.MaritimeSnapshot;
import com.drift.backend.shipment.PortCatalog;

import tools.jackson.databind.ObjectMapper;

class MaritimeIngestSourcesTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Instant RETRIEVED = Instant.parse("2026-11-03T02:05:00Z");
	private static final URI ENDPOINT = URI.create("https://example.test/data");
	private static final List<PortCatalog.Coordinates> SINGAPORE = List.of(new PortCatalog.Coordinates(103.82, 1.26));

	@Test
	void keepsForecastAreasNearThePortWithTheirValidPeriod() {
		String body = """
				{"area_metadata":[
				  {"name":"Bukit Merah","label_location":{"latitude":1.277,"longitude":103.819}},
				  {"name":"Woodlands","label_location":{"latitude":1.432,"longitude":103.786}}],
				 "items":[{"update_timestamp":"2026-11-03T10:00:00+08:00",
				  "valid_period":{"start":"2026-11-03T10:00:00+08:00","end":"2026-11-03T12:00:00+08:00"},
				  "forecasts":[{"area":"Bukit Merah","forecast":"Thundery Showers"},{"area":"Woodlands","forecast":"Fair"}]}]}
				""";

		List<MaritimeSnapshot> snapshots = NeaWeatherSource.forecasts(JSON.readTree(body), SINGAPORE, 10, RETRIEVED, ENDPOINT);

		assertThat(snapshots).singleElement().satisfies(snapshot -> {
			assertThat(snapshot.sourceType()).isEqualTo("NEA_WEATHER");
			assertThat(snapshot.sourceIdentity()).isEqualTo("nea:forecast:Bukit Merah");
			assertThat(snapshot.sourceMode()).isEqualTo("LIVE");
			assertThat(snapshot.location()).isEqualTo("Bukit Merah");
			assertThat(snapshot.effectiveFrom()).isEqualTo("2026-11-03T02:00:00Z");
			assertThat(snapshot.effectiveUntil()).isEqualTo("2026-11-03T04:00:00Z");
			assertThat(snapshot.currentValue()).isEqualTo("Thundery Showers");
		});
	}

	@Test
	void storesRainfallPerHourAndWindInKnotsForNearbyStations() {
		String rain = """
				{"stations":[{"id":"S60","name":"Sentosa","location":{"latitude":1.25,"longitude":103.8279}}],
				 "readings":[{"timestamp":"2026-11-03T10:05:00+08:00","data":[{"stationId":"S60","value":1.2}]}],
				 "readingUnit":"mm"}
				""";
		String wind = """
				{"stations":[{"id":"S60","name":"Sentosa","location":{"latitude":1.25,"longitude":103.8279}}],
				 "readings":[{"timestamp":"2026-11-03T10:05:00+08:00","data":[{"stationId":"S60","value":24.06}]}],
				 "readingUnit":"knots"}
				""";

		MaritimeSnapshot rainfall = NeaWeatherSource.readings(JSON.readTree(rain), NeaWeatherSource.RAINFALL, SINGAPORE, 10,
				RETRIEVED, ENDPOINT).getFirst();
		MaritimeSnapshot speed = NeaWeatherSource.readings(JSON.readTree(wind), NeaWeatherSource.WIND, SINGAPORE, 10,
				RETRIEVED, ENDPOINT).getFirst();

		assertThat(rainfall.currentValue()).isEqualTo("Rainfall 14.4 mm/h");
		assertThat(rainfall.effectiveUntil()).isEqualTo("2026-11-03T02:35:00Z");
		assertThat(speed.currentValue()).isEqualTo("Wind 24.1 knots");
		assertThat(speed.sourceIdentity()).isEqualTo("nea:wind:S60");
	}

	@Test
	void anNeaErrorCodeSkipsTheSource() {
		NeaWeatherSource source = new NeaWeatherSource(
				new MaritimeIngestProperties.Nea(true, URI.create("https://example.test/api"), null, List.of("singapore"), 25),
				(uri, headers) -> "{\"code\":4,\"data\":{}}", PortCatalog.load());

		assertThatThrownBy(() -> source.fetch(RETRIEVED)).isInstanceOf(MaritimeSourceException.class);
	}

	@Test
	void readsNoticesWithTheirReleaseDateAndPlace() {
		String body = """
				{"data":[
				  {"id":"abc","title":"Temporary closure of Tuas fairway","release_date":"2026-11-01","status":"Active"},
				  {"id":"def","title":"Port dues revision","release_date":"2026-10-30T09:00:00"},
				  {"id":"","title":"No id","release_date":"2026-10-30"}]}
				""";

		List<MaritimeSnapshot> notices = MpaNoticeSource.notices(JSON.readTree(body), RETRIEVED, ENDPOINT);

		assertThat(notices).hasSize(2);
		assertThat(notices.getFirst()).satisfies(notice -> {
			assertThat(notice.sourceIdentity()).isEqualTo("mpa:notice:abc");
			assertThat(notice.location()).isEqualTo("Tuas, Singapore");
			assertThat(notice.effectiveFrom()).isEqualTo("2026-10-31T16:00:00Z");
			assertThat(notice.currentValue()).isEqualTo("Temporary closure of Tuas fairway [Active]");
		});
		assertThat(notices.get(1).location()).isEqualTo("Singapore");
	}

	@Test
	void readsExpectedArrivalsWithAValidImo() {
		String body = """
				[{"vesselParticulars":{"imoNumber":"9351048","vesselName":"KOTA LARIS"},"duetoArriveTime":"2026-11-05 16:00:00"},
				 {"vesselParticulars":{"imoNumber":"12"},"duetoArriveTime":"2026-11-05 16:00:00"},
				 {"vesselParticulars":{"imoNumber":"9387421"}}]
				""";

		List<MpaExpectedArrivalSource.Arrival> arrivals = MpaExpectedArrivalSource.arrivals(JSON.readTree(body));

		assertThat(arrivals).singleElement().satisfies(arrival -> {
			assertThat(arrival.imo()).isEqualTo("9351048");
			assertThat(arrival.expected()).isEqualTo(Instant.parse("2026-11-05T08:00:00Z"));
		});
		MaritimeSnapshot snapshot = MpaExpectedArrivalSource.snapshot(arrivals.getFirst(), null, RETRIEVED);
		assertThat(snapshot.vesselImo()).isEqualTo("9351048");
		assertThat(snapshot.effectiveFrom()).isEqualTo("2026-11-02T08:00:00Z");
		assertThat(snapshot.effectiveUntil()).isEqualTo("2026-11-08T08:00:00Z");
	}

	@Test
	void anUnchangedArrivalKeepsItsPreviousValueSoNoRevisionIsRecorded() {
		Instant expected = Instant.parse("2026-11-05T08:00:00Z");
		CurrentMaritimeSignals.HeadValues head = new CurrentMaritimeSignals.HeadValues("2026-11-05T05:00:00Z",
				"2026-11-05T08:00:00Z");

		assertThat(MpaExpectedArrivalSource.previous(Optional.of(head), expected)).isEqualTo("2026-11-05T05:00:00Z");
		assertThat(MpaExpectedArrivalSource.previous(Optional.of(head), expected.plusSeconds(3600)))
				.isEqualTo("2026-11-05T08:00:00Z");
		assertThat(MpaExpectedArrivalSource.previous(Optional.empty(), expected)).isNull();
	}

	@Test
	void readsSourceTimesWithAndWithoutAnOffset() {
		assertThat(SourceTimes.parse("2026-11-03T10:00:00+08:00")).isEqualTo(Instant.parse("2026-11-03T02:00:00Z"));
		assertThat(SourceTimes.parse("2026-11-03 10:00:00")).isEqualTo(Instant.parse("2026-11-03T02:00:00Z"));
		assertThat(SourceTimes.parse("2026-11-03")).isEqualTo(Instant.parse("2026-11-02T16:00:00Z"));
		assertThat(SourceTimes.parse("soon")).isNull();
	}

	@Test
	void theOceansxSourceStaysOffWithoutAKey() {
		MaritimeIngestProperties properties = new MaritimeIngestProperties(true, null, null,
				new MaritimeIngestProperties.MpaOceansx("", "https://example.test", "/due/{date}", 3));

		assertThat(new MpaExpectedArrivalSource(properties, (uri, headers) -> "[]", null).enabled()).isFalse();
	}
}
