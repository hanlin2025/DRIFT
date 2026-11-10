package com.drift.backend.maritimechange;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded polling cycle per interval. Enable only on one persistent worker instance. */
@Component
@ConditionalOnProperty(name = "app.maritime-change.collection.enabled", havingValue = "true")
class MaritimeSnapshotCollectionScheduler {
	private static final long MIN_INTERVAL_MS = 300_000L;
	private static final long MAX_INTERVAL_MS = 86_400_000L;
	private static final long MAX_INITIAL_DELAY_MS = 86_400_000L;

	private final MaritimeSnapshotPollingService polling;
	private final MaritimeSnapshotChangeDetector detector;
	private final MaritimeCollectionHealth health;
	private final AtomicBoolean running = new AtomicBoolean();

	MaritimeSnapshotCollectionScheduler(MaritimeSnapshotPollingService polling,
			MaritimeSnapshotChangeDetector detector, MaritimeCollectionHealth health,
			@Value("${app.maritime-change.collection.interval-ms:1800000}") long intervalMs,
			@Value("${app.maritime-change.collection.initial-delay-ms:30000}") long initialDelayMs) {
		if (intervalMs < MIN_INTERVAL_MS || intervalMs > MAX_INTERVAL_MS
				|| initialDelayMs < 0 || initialDelayMs > MAX_INITIAL_DELAY_MS) {
			throw new IllegalArgumentException("Maritime collection schedule is outside the bounded interval limits");
		}
		this.polling = polling;
		this.detector = detector;
		this.health = health;
	}

	@Scheduled(fixedDelayString = "${app.maritime-change.collection.interval-ms:1800000}",
			initialDelayString = "${app.maritime-change.collection.initial-delay-ms:30000}")
	void pollOnce() {
		if (!running.compareAndSet(false, true)) return;
		try {
			for (MaritimeCollectionPoll result : polling.poll()) {
				try {
					if (result.successful() && !result.snapshots().isEmpty()) detector.accept(result.snapshots());
					health.record(result.source(), result.successful());
				} catch (RuntimeException ex) {
					// A partial/failed feed never clears a previously persisted valid head.
					health.record(result.source(), false);
				}
			}
		} finally {
			running.set(false);
		}
	}
}
