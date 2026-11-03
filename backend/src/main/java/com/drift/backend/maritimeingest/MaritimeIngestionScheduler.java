package com.drift.backend.maritimeingest;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.drift.backend.maritimechange.MaritimeSnapshot;
import com.drift.backend.maritimechange.MaritimeSnapshotChangeBatch;
import com.drift.backend.maritimechange.MaritimeSnapshotChangeDetector;

/**
 * Collects every enabled live source and stores what changed. Each source is read and stored on its own,
 * so one source that is down or malformed does not stop the others.
 */
@Component
public class MaritimeIngestionScheduler {

	private static final Logger log = LoggerFactory.getLogger(MaritimeIngestionScheduler.class);

	private final MaritimeIngestProperties properties;
	private final List<MaritimeSnapshotSource> sources;
	private final MaritimeSnapshotChangeDetector detector;
	private final Clock clock;

	MaritimeIngestionScheduler(MaritimeIngestProperties properties, List<MaritimeSnapshotSource> sources,
			MaritimeSnapshotChangeDetector detector, ObjectProvider<Clock> clocks) {
		this.properties = properties;
		this.sources = List.copyOf(sources);
		this.detector = detector;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Scheduled(fixedDelayString = "${app.maritime-ingest.interval:15m}")
	public void scheduledCollect() {
		if (properties.enabled()) {
			collect();
		}
	}

	public void collect() {
		for (MaritimeSnapshotSource source : sources) {
			if (!source.enabled()) {
				continue;
			}
			try {
				List<MaritimeSnapshot> snapshots = source.fetch(clock.instant());
				MaritimeSnapshotChangeBatch batch = detector.accept(snapshots);
				log.info("{}: {} snapshots, {} changes, {} rejected", source.name(), snapshots.size(),
						batch.changes().size(), batch.rejections().size());
			} catch (RuntimeException exception) {
				log.warn("{} could not be collected: {}", source.name(), exception.getMessage());
			}
		}
	}
}
