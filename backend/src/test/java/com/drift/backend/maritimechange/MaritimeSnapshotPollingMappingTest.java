package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MaritimeSnapshotPollingMappingTest {
	private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");
	private static final Set<String> PORT_AREAS = MaritimeSnapshotPollingService.areaSet("Tuas, bukit  merah");

	@Test
	void neaForecastsKeepOnlyConfiguredPortAreasAndTheTwoHourDataset() {
		List<MaritimeSourceObservation> observations = MaritimeSnapshotPollingService.neaForecastObservations(List.of(
				forecast("Tuas", "Cloudy", "2026-11-11T13:00:00+08:00"),
				forecast("Ang Mo Kio", "Cloudy", "2026-11-11T13:00:00+08:00"),
				forecast("Bukit Merah", "", "2026-11-11T13:00:00+08:00"),
				Map.of("dataset", "rainfall", "station_id", "S306", "value", "0", "unit", "mm",
						"source_timestamp", "2026-11-11T13:00:00+08:00", "source_raw_file", "raw.json")),
				Map.of("raw.json", "2026-11-11T05:01:00Z"), "nea.csv", PORT_AREAS, NOW);

		assertThat(observations).singleElement().satisfies(observation -> {
			assertThat(observation.sourceIdentity()).isEqualTo("nea:two_hr_forecast:Tuas");
			assertThat(observation.currentValue()).isEqualTo("Cloudy");
			assertThat(observation.location()).isEqualTo("Tuas");
			assertThat(observation.effectiveFrom()).isEqualTo("2026-11-11T05:00:00Z");
			assertThat(observation.effectiveUntil()).isEqualTo("2026-11-11T07:00:00Z");
			assertThat(observation.retrievedAtUtc()).isEqualTo("2026-11-11T05:01:00Z");
			assertThat(observation.sourceMode()).isEqualTo("LIVE");
			assertThat(observation.freshness()).isEqualTo("APPLICABLE");
			assertThat(observation.timeCompatibility()).isNull();
		});
	}

	@Test
	void aReissuedUnchangedForecastIsNotANewEventButAChangedForecastIsARevision() {
		MaritimeSnapshotNormalizer normalizer = new MaritimeSnapshotNormalizer();
		Memory store = new Memory();
		MaritimeSnapshotChangeDetector detector = new MaritimeSnapshotChangeDetector(store, Clock.fixed(NOW, ZoneOffset.UTC));
		List<String> polls = List.of("Cloudy", "Cloudy", "Thundery Showers");
		List<MaritimeSnapshotChange> changes = new ArrayList<>();
		for (int index = 0; index < polls.size(); index++) {
			String issued = "2026-11-11T1" + (3 + index) + ":00:00+08:00";
			List<MaritimeSourceObservation> observations = MaritimeSnapshotPollingService.neaForecastObservations(
					List.of(forecast("Tuas", polls.get(index), issued)),
					Map.of("raw.json", "2026-11-11T0" + (5 + index) + ":01:00Z"), "nea.csv", PORT_AREAS, NOW);
			changes.addAll(detector.accept(observations.stream().map(normalizer::normalize).toList()).changes());
		}

		assertThat(changes).extracting(MaritimeSnapshotChange::kind).containsExactly("NEW", "REVISED");
		assertThat(changes.get(1).previousValue()).isEqualTo("Cloudy");
		assertThat(changes.get(1).currentValue()).isEqualTo("Thundery Showers");
		assertThat(store.rows).hasSize(2);
	}

	@Test
	void noticesOutsideTheLookbackAreNotTreatedAsNewLiveChanges() {
		assertThat(MaritimeSnapshotPollingService.noticeWithinLookback("2026-11-01T00:00:00+08:00", NOW, 14)).isTrue();
		assertThat(MaritimeSnapshotPollingService.noticeWithinLookback("2026-10-01T00:00:00+08:00", NOW, 14)).isFalse();
		assertThat(MaritimeSnapshotPollingService.noticeWithinLookback(null, NOW, 14)).isTrue();
	}

	@Test
	void blankAreaConfigurationFallsBackToTheDefaultPortAreas() {
		assertThat(MaritimeSnapshotPollingService.areaSet(" "))
				.isEqualTo(MaritimeSnapshotPollingService.areaSet(MaritimeSnapshotPollingService.DEFAULT_NEA_AREAS))
				.contains("tuas", "bukit merah");
	}

	private static Map<String, String> forecast(String area, String text, String issued) {
		return Map.of("dataset", "two_hr_forecast", "area", area, "forecast", text,
				"forecast_issuance_timestamp", issued, "source_timestamp", issued,
				"valid_start", "2026-11-11T13:00:00+08:00", "valid_end", "2026-11-11T15:00:00+08:00",
				"source_raw_file", "raw.json");
	}

	private static final class Memory implements MaritimeSnapshotObservationStore {
		private final List<MaritimeSnapshotObservation> rows = new ArrayList<>();

		@Override
		public Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity) {
			return rows.stream().filter(row -> row.isHead() && row.getSourceType().equals(sourceType)
					&& row.getSourceIdentity().equals(sourceIdentity)).findFirst();
		}

		@Override
		public Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String contentFingerprint) {
			return rows.stream().filter(row -> row.getSourceType().equals(sourceType)
					&& row.getSourceIdentity().equals(sourceIdentity)
					&& row.getContentFingerprint().equals(contentFingerprint)).findFirst();
		}

		@Override
		public void add(MaritimeSnapshotObservation observation, boolean becomesHead) {
			if (becomesHead) {
				rows.stream().filter(row -> row.isHead() && row.getSourceType().equals(observation.getSourceType())
						&& row.getSourceIdentity().equals(observation.getSourceIdentity()))
						.forEach(MaritimeSnapshotObservation::clearHead);
				observation.markHead();
			}
			rows.add(observation);
		}
	}
}
