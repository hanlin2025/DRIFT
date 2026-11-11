package com.drift.backend.maritimechange;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

interface MaritimeSnapshotObservationStore {

	Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity);

	Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String contentFingerprint);

	default Optional<MaritimeSnapshotObservation> latestMpaHeadForImo(String imo) { return Optional.empty(); }
	void add(MaritimeSnapshotObservation observation, boolean becomesHead);

	default List<MaritimeSnapshotObservation> before(Instant detectedSince, Instant beforeRecordedAt, long beforeId, int limit) {
		return List.of();
	}
}
