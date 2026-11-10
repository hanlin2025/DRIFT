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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
				health, Runnable::run, 1_800_000L, 30_000L);

		scheduler.pollOnce();

		assertThat(store.headValue()).isEqualTo("arrival=12:00");
		assertThat(store.size()).isEqualTo(1);
		assertThat(health.availability()).isEqualTo("UNAVAILABLE");
	}

	@Test
	void nonemptyBatchRejectedInFullMarksSourceUnavailable() {
		MemoryStore store = new MemoryStore();
		MaritimeSnapshotChangeDetector detector = new MaritimeSnapshotChangeDetector(store,
				Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
		MaritimeSnapshotPollingService polling = mock(MaritimeSnapshotPollingService.class);
		when(polling.poll()).thenReturn(List.of(new MaritimeCollectionPoll("NEA_WEATHER", true,
				List.of(new MaritimeSnapshot("INVALID_SOURCE", "nea:rainfall:S306", "NEA poll", "2026-11-10T00:00:00Z",
						"2026-11-10T00:00:00Z", "2026-11-10T00:00:00Z", "LIVE", "APPLICABLE", null, "Singapore",
						null, null, null, "rainfall=1", "UNKNOWN", "UNKNOWN", "UNKNOWN")))));
		MaritimeCollectionHealth health = new MaritimeCollectionHealth();
		new MaritimeSnapshotCollectionScheduler(polling, detector, health, Runnable::run, 1_800_000L, 30_000L).pollOnce();

		assertThat(store.size()).isZero();
		assertThat(health.availability()).isEqualTo("UNAVAILABLE");
	}

	@Test
	void scheduledTickReturnsWhileSingleCollectorWorkerIsBlockedAndDoesNotOverlap() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<Thread> collectorThread = new AtomicReference<>();
		MaritimeSnapshotPollingService polling = mock(MaritimeSnapshotPollingService.class);
		when(polling.poll()).thenAnswer(invocation -> {
			collectorThread.set(Thread.currentThread());
			entered.countDown();
			assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
			finished.countDown();
			return List.of(new MaritimeCollectionPoll("MPA_EXPECTED_ARRIVALS", true, List.of()));
		});
		ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "test-maritime-worker"));
		MaritimeSnapshotCollectionScheduler scheduler = new MaritimeSnapshotCollectionScheduler(polling,
				mock(MaritimeSnapshotChangeDetector.class), new MaritimeCollectionHealth(), worker, 1_800_000L, 30_000L);
		try {
			scheduler.pollOnce();
			assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
			assertThat(collectorThread.get()).isNotSameAs(Thread.currentThread());
			scheduler.pollOnce();
			release.countDown();
			assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
			verify(polling, times(1)).poll();
		} finally {
			release.countDown();
			worker.shutdownNow();
		}
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
