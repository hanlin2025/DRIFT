package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class MaritimeSnapshotNormalizerTest {

	@Test
	void mapsLiveAndReplaySourceObservationsIntoTheSameSnapshotContract() {
		MaritimeSnapshotNormalizer normalizer = new MaritimeSnapshotNormalizer();
		MaritimeSourceObservation source = new MaritimeSourceObservation("NEA_WEATHER", "nea:rainfall:S306",
				"NEA data.gov.sg rainfall", "2026-10-24T22:25:00+08:00", "2026-10-24T22:25:00+08:00",
				"2026-10-26T08:44:24.401269Z", "RECORDED_REPLAY", "HISTORICAL", null, "Singapore", null, null,
				"0 mm", "UNKNOWN", "UNKNOWN", "UNKNOWN");

		MaritimeSnapshot snapshot = normalizer.normalize(source);

		assertThat(snapshot.sourceType()).isEqualTo(source.sourceType());
		assertThat(snapshot.sourceIdentity()).isEqualTo(source.sourceIdentity());
		assertThat(snapshot.sourceEventAt()).isEqualTo(source.sourceEventAt());
		assertThat(snapshot.observedAt()).isEqualTo(source.observedAt());
		assertThat(snapshot.retrievedAtUtc()).isEqualTo(source.retrievedAtUtc());
		assertThat(snapshot.sourceMode()).isEqualTo("RECORDED_REPLAY");
		assertThat(snapshot.previousValue()).isNull();
		assertThat(snapshot.currentValue()).isEqualTo("0 mm");
	}

	@Test
	void convertsMpaLocalExpectedTimesToSingaporeOffsetAndRejectsUnknownFormats() {
		assertThat(MaritimeSnapshotPollingService.normalizeMpaTime("2026-10-28 23:00:00"))
				.isEqualTo("2026-10-28T23:00:00+08:00");
		// OffsetDateTime.toString() omits :00 seconds; the source contract must keep them.
		assertThat(MaritimeSnapshotPollingService.normalizeMpaTime("2026-10-29T02:00+08:00"))
				.isEqualTo("2026-10-29T02:00:00+08:00");
		assertThat(MaritimeSnapshotPollingService.normalizeMpaTime("2026-10-29T02:00:00Z"))
				.isEqualTo("2026-10-29T10:00:00+08:00");
		assertThat(MaritimeSnapshotPollingService.normalizeMpaTime("not-a-source-time")).isNull();
	}

	@Test
	void convertsMpaNoticePublicationDateToSingaporeStartOfDay() {
		assertThat(MaritimeSnapshotPollingService.normalizeMpaNoticeTime("2026-10-28"))
				.isEqualTo("2026-10-28T00:00:00+08:00");
		assertThat(MaritimeSnapshotPollingService.normalizeMpaNoticeTime("2026-10-28T02:00:00Z"))
				.isEqualTo("2026-10-28T10:00:00+08:00");
		assertThat(MaritimeSnapshotPollingService.normalizeMpaNoticeTime("invalid-date")).isNull();
	}

	@Test
	void pollerShapedMpaTimestampWithZeroSecondsIsAcceptedByTheSnapshotDetector() {
		String arrival = MaritimeSnapshotPollingService.normalizeMpaTime("2026-11-12T13:30+08:00");
		String departure = MaritimeSnapshotPollingService.normalizeMpaTime("2026-11-12T15:45+08:00");
		MaritimeSnapshotNormalizer normalizer = new MaritimeSnapshotNormalizer();
		MaritimeSnapshot snapshot = normalizer.normalize(new MaritimeSourceObservation(
				MaritimeSnapshotChangeDetector.MPA_ETA_REVISION, "mpa:9351048:SEA:SGSIN", "MPA poll fixture",
				arrival, "2026-11-11T00:00:00Z", "2026-11-11T00:00:00Z", "LIVE", "APPLICABLE", "9351048",
				"Singapore", arrival, departure, "arrival=2026-11-12 13:30:00;departure=2026-11-12 15:45:00",
				"UNKNOWN", "UNKNOWN", "UNKNOWN"));
		MemoryStore store = new MemoryStore();
		MaritimeSnapshotChangeDetector detector = new MaritimeSnapshotChangeDetector(store,
				Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneOffset.UTC));

		MaritimeSnapshotChangeBatch result = detector.accept(List.of(snapshot));

		assertThat(result.rejections()).isEmpty();
		assertThat(result.changes()).singleElement().satisfies(change -> {
			assertThat(change.sourceEventAt()).isEqualTo("2026-11-12T13:30:00+08:00");
			assertThat(change.effectiveFrom()).isEqualTo("2026-11-12T13:30:00+08:00");
			assertThat(change.effectiveUntil()).isEqualTo("2026-11-12T15:45:00+08:00");
			assertThat(change.forwardLooking()).isTrue();
		});
		assertThat(store.rows).hasSize(1);
	}

	private static final class MemoryStore implements MaritimeSnapshotObservationStore {
		private final List<MaritimeSnapshotObservation> rows = new ArrayList<>();
		@Override public Optional<MaritimeSnapshotObservation> head(String type, String identity) { return Optional.empty(); }
		@Override public Optional<MaritimeSnapshotObservation> find(String type, String identity, String fingerprint) { return Optional.empty(); }
		@Override public void add(MaritimeSnapshotObservation observation, boolean becomesHead) { rows.add(observation); }
	}
}
