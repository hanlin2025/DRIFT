package com.drift.backend.maritimeingest;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.drift.backend.maritimechange.MaritimeSnapshot;
import com.drift.backend.maritimechange.MaritimeSnapshotChangeBatch;
import com.drift.backend.maritimechange.MaritimeSnapshotChangeDetector;

class MaritimeIngestionSchedulerTest {

	@Test
	void aFailingSourceDoesNotStopTheOthers() {
		MaritimeSnapshotChangeDetector detector = mock(MaritimeSnapshotChangeDetector.class);
		when(detector.accept(anyList())).thenReturn(new MaritimeSnapshotChangeBatch(List.of(), List.of()));
		MaritimeIngestionScheduler scheduler = new MaritimeIngestionScheduler(properties(true),
				List.of(source(true, true), source(true, false), source(false, false)), detector, noClock());

		scheduler.scheduledCollect();

		verify(detector, times(1)).accept(anyList());
	}

	@Test
	void nothingIsCollectedWhenIngestIsOff() {
		MaritimeSnapshotChangeDetector detector = mock(MaritimeSnapshotChangeDetector.class);
		MaritimeIngestionScheduler scheduler = new MaritimeIngestionScheduler(properties(false),
				List.of(source(true, false)), detector, noClock());

		scheduler.scheduledCollect();

		verify(detector, never()).accept(anyList());
	}

	private static MaritimeIngestProperties properties(boolean enabled) {
		return new MaritimeIngestProperties(enabled, null, null, null);
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<java.time.Clock> noClock() {
		return mock(ObjectProvider.class, invocation -> invocation.getMethod().getName().equals("getIfAvailable")
				? java.time.Clock.systemUTC()
				: null);
	}

	private static MaritimeSnapshotSource source(boolean enabled, boolean fails) {
		return new MaritimeSnapshotSource() {
			@Override
			public String name() {
				return "test";
			}

			@Override
			public boolean enabled() {
				return enabled;
			}

			@Override
			public List<MaritimeSnapshot> fetch(Instant retrievedAt) {
				if (fails) {
					throw new MaritimeSourceException("down");
				}
				return List.of();
			}
		};
	}
}
