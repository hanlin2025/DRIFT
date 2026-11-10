package com.drift.backend.maritimechange;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

interface MaritimeSnapshotObservationStore {

	Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity);

	Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String contentFingerprint);

	void add(MaritimeSnapshotObservation observation, boolean becomesHead);

	default List<MaritimeSnapshotObservation> after(Instant detectedSince, Instant afterRecordedAt, long afterId, int limit) {
		return List.of();
	}
}
