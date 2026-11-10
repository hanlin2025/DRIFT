package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class MaritimeRecordedSnapshotReplayTest {

	@Test
	void authenticReplayUsesTheSharedNormalizerAndDetectorAndIsIdempotent() {
		MemoryStore store = new MemoryStore();
		MaritimeSnapshotChangeDetector detector = new MaritimeSnapshotChangeDetector(store);
		MaritimeCollectionHealth health = new MaritimeCollectionHealth();
		MaritimeRecordedSnapshotReplay replay = new MaritimeRecordedSnapshotReplay(new ObjectMapper(),
				new MaritimeSnapshotNormalizer(), detector, health);

		MaritimeSnapshotChangeBatch first = replay.replay();
		MaritimeSnapshotChangeBatch repeated = replay.replay();

		assertThat(first.rejections()).isEmpty();
		assertThat(first.changes()).hasSize(5);
		assertThat(first.changes()).filteredOn(change -> change.sourceType().equals("MPA_ETA_REVISION"))
				.extracting(MaritimeSnapshotChange::kind).containsExactly("NEW", "REVISED");
		assertThat(first.changes()).filteredOn(change -> change.sourceType().equals("MPA_ETA_REVISION"))
				.last().satisfies(change -> {
					assertThat(change.previousValue()).isEqualTo("2026-10-28T23:00:00+08:00");
					assertThat(change.currentValue()).isEqualTo("2026-10-29T02:00:00+08:00");
					assertThat(change.sourceMode()).isEqualTo("RECORDED_REPLAY");
					assertThat(change.forwardLooking()).isFalse();
				});
		assertThat(first.changes()).filteredOn(change -> change.sourceType().equals("MPA_OFFICIAL_NOTICE"))
				.singleElement().extracting(MaritimeSnapshotChange::kind).isEqualTo("NEW");
		assertThat(first.changes()).filteredOn(change -> change.sourceType().equals("NEA_WEATHER"))
				.extracting(MaritimeSnapshotChange::kind).containsExactly("NEW", "REVISED");
		assertThat(first.changes()).filteredOn(change -> change.sourceType().equals("NEA_WEATHER"))
				.last().satisfies(change -> {
					assertThat(change.previousValue()).isEqualTo("0.2 mm");
					assertThat(change.currentValue()).isEqualTo("0 mm");
				});
		assertThat(repeated.changes()).isEmpty();
		assertThat(repeated.rejections()).isEmpty();
		assertThat(store.size()).isEqualTo(5);
		assertThat(health.availability()).isEqualTo("AVAILABLE");
	}

	private static final class MemoryStore implements MaritimeSnapshotObservationStore {
		private final Map<String, MaritimeSnapshotObservation> heads = new HashMap<>();
		private final Map<String, MaritimeSnapshotObservation> all = new HashMap<>();

		@Override public Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity) {
			return Optional.ofNullable(heads.get(key(sourceType, sourceIdentity)));
		}

		@Override public Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity,
				String contentFingerprint) {
			return Optional.ofNullable(all.get(key(sourceType, sourceIdentity) + ":" + contentFingerprint));
		}

		@Override public void add(MaritimeSnapshotObservation observation, boolean becomesHead) {
			all.put(key(observation.getSourceType(), observation.getSourceIdentity()) + ":" + observation.getContentFingerprint(), observation);
			if (becomesHead) heads.put(key(observation.getSourceType(), observation.getSourceIdentity()), observation);
		}

		int size() { return all.size(); }
		private static String key(String sourceType, String identity) { return sourceType + ":" + identity; }
	}
}
