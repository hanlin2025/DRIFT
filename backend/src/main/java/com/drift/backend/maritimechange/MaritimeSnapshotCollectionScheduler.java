package com.drift.backend.maritimechange;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

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
	private final Executor collectionExecutor;
	private final AtomicBoolean running = new AtomicBoolean();

	MaritimeSnapshotCollectionScheduler(MaritimeSnapshotPollingService polling,
			MaritimeSnapshotChangeDetector detector, MaritimeCollectionHealth health,
			@Qualifier("maritimeCollectionExecutor") Executor collectionExecutor,
			@Value("${app.maritime-change.collection.interval-ms:1800000}") long intervalMs,
			@Value("${app.maritime-change.collection.initial-delay-ms:30000}") long initialDelayMs) {
		if (intervalMs < MIN_INTERVAL_MS || intervalMs > MAX_INTERVAL_MS
				|| initialDelayMs < 0 || initialDelayMs > MAX_INITIAL_DELAY_MS) {
			throw new IllegalArgumentException("Maritime collection schedule is outside the bounded interval limits");
		}
		this.polling = polling;
		this.detector = detector;
		this.health = health;
		this.collectionExecutor = collectionExecutor;
	}

	@Scheduled(fixedDelayString = "${app.maritime-change.collection.interval-ms:1800000}",
			initialDelayString = "${app.maritime-change.collection.initial-delay-ms:30000}")
	void pollOnce() {
		if (!running.compareAndSet(false, true)) return;
		try {
			collectionExecutor.execute(this::pollCycle);
		} catch (RuntimeException ex) {
			running.set(false);
			LoggerFactory.getLogger(getClass()).warn("Maritime collection worker could not start exception={}",
					ex.getClass().getSimpleName());
		}
	}

	private void pollCycle() {
		try {
			for (MaritimeCollectionPoll result : polling.poll()) {
				try {
					boolean healthy = result.successful();
					if (healthy && !result.snapshots().isEmpty()) {
						MaritimeSnapshotChangeBatch batch = detector.accept(result.snapshots());
						Map<String, Long> rejectionCounts = new TreeMap<>();
						batch.rejections().forEach(rejection -> rejectionCounts.merge(rejection.reason(), 1L, Long::sum));
						if (!rejectionCounts.isEmpty()) {
							LoggerFactory.getLogger(getClass()).warn(
									"Maritime source observations rejected source={} rejected={} reasons={}",
									result.source(), batch.rejections().size(), rejectionCounts);
						}
						if (batch.rejections().size() == result.snapshots().size()) healthy = false;
					}
					health.record(result.source(), healthy);
				} catch (RuntimeException ex) {
					// A partial/failed feed never clears a previously persisted valid head.
					health.record(result.source(), false);
					LoggerFactory.getLogger(getClass()).warn(
							"Maritime source processing failed source={} exception={}", result.source(), ex.getClass().getSimpleName());
				}
			}
		} finally {
			running.set(false);
		}
	}
}
