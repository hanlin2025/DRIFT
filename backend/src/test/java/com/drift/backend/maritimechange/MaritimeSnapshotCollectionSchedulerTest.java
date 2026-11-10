package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class MaritimeSnapshotCollectionSchedulerTest {
	@Test
	void failedPollingCannotApplyPartialSnapshotsOrRemoveThePriorHead() {
		MemoryStore store = new MemoryStore();
		MaritimeSnapshotChangeDetector detector = new MaritimeSnapshotChangeDetector(store,
				Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
		detector.accept(List.of(snapshot("2026-11-09T00:00:00Z", "arrival=12:00")));
		MaritimeSnapshotPollingService polling = mock(MaritimeSnapshotPollingService.class);
		when(polling.poll()).thenReturn(List.of(new MaritimeCollectionPoll("MPA_EXPECTED_ARRIVALS", false,
				List.of(snapshot("2026-11-10T00:00:00Z", "arrival=15:00")))));
		MaritimeCollectionHealth health = new MaritimeCollectionHealth();
		MaritimeSnapshotCollectionScheduler scheduler = new MaritimeSnapshotCollectionScheduler(polling, detector,
				health, 1_800_000L, 30_000L);

		scheduler.pollOnce();

		assertThat(store.headValue()).isEqualTo("arrival=12:00");
		assertThat(store.size()).isEqualTo(1);
		assertThat(health.availability()).isEqualTo("UNAVAILABLE");
	}

	private static MaritimeSnapshot snapshot(String retrievedAt, String value) {
		return new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:9351048:SEA:PEBGA", "MPA collector", "2026-11-11T00:00:00Z",
			retrievedAt, retrievedAt, "LIVE", "APPLICABLE", "9351048", "Singapore", null, null, null, value,
			"UNKNOWN", "UNKNOWN", "UNKNOWN");
	}

	private static final class MemoryStore implements MaritimeSnapshotObservationStore {
		private final List<MaritimeSnapshotObservation> rows = new ArrayList<>();
		@Override public Optional<MaritimeSnapshotObservation> head(String type, String identity) {
			return rows.stream().filter(MaritimeSnapshotObservation::isHead).findFirst();
		}
		@Override public Optional<MaritimeSnapshotObservation> find(String type, String identity, String fingerprint) {
			return rows.stream().filter(row -> row.getContentFingerprint().equals(fingerprint)).findFirst();
		}
		@Override public void add(MaritimeSnapshotObservation observation, boolean becomesHead) {
			if (becomesHead) rows.stream().filter(MaritimeSnapshotObservation::isHead).forEach(MaritimeSnapshotObservation::clearHead);
			if (becomesHead) observation.markHead();
			rows.add(observation);
		}
		String headValue() { return rows.stream().filter(MaritimeSnapshotObservation::isHead).map(MaritimeSnapshotObservation::getCurrentValue).findFirst().orElse(null); }
		int size() { return rows.size(); }
	}
}
