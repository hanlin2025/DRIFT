package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;

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
				.isEqualTo("2026-10-28T23:00+08:00");
		assertThat(MaritimeSnapshotPollingService.normalizeMpaTime("2026-10-29T02:00:00Z"))
				.isEqualTo("2026-10-29T10:00+08:00");
		assertThat(MaritimeSnapshotPollingService.normalizeMpaTime("not-a-source-time")).isNull();
	}

	@Test
	void convertsMpaNoticePublicationDateToSingaporeStartOfDay() {
		assertThat(MaritimeSnapshotPollingService.normalizeMpaNoticeTime("2026-10-28"))
				.isEqualTo("2026-10-28T00:00+08:00");
		assertThat(MaritimeSnapshotPollingService.normalizeMpaNoticeTime("2026-10-28T02:00:00Z"))
				.isEqualTo("2026-10-28T10:00+08:00");
		assertThat(MaritimeSnapshotPollingService.normalizeMpaNoticeTime("invalid-date")).isNull();
	}
}
