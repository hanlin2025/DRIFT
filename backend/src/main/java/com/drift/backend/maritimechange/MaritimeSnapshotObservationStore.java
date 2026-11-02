package com.drift.backend.maritimechange;

import java.util.Optional;

interface MaritimeSnapshotObservationStore {

	Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity);

	Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String contentFingerprint);

	void add(MaritimeSnapshotObservation observation, boolean becomesHead);
}
