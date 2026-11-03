package com.drift.backend.maritimechange;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface MaritimeSnapshotObservationStore {

	Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity);

	Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String contentFingerprint);

	void add(MaritimeSnapshotObservation observation, boolean becomesHead);

	/** Head rows from a live, applicable retrieval whose effective period has not ended at {@code now}. */
	List<MaritimeSnapshotObservation> currentHeads(Instant now);
}
