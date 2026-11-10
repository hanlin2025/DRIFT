package com.drift.backend.maritimechange;

import org.springframework.stereotype.Component;

/** Shared live/replay normalization into the existing snapshot detector contract. */
@Component
class MaritimeSnapshotNormalizer {

	MaritimeSnapshot normalize(MaritimeSourceObservation source) {
		if (source == null) throw new IllegalArgumentException("Source observation is required");
		return new MaritimeSnapshot(source.sourceType(), source.sourceIdentity(), source.provenance(),
				source.sourceEventAt(), source.observedAt(), source.retrievedAtUtc(), source.sourceMode(),
				source.freshness(), source.vesselImo(), source.location(), source.effectiveFrom(), source.effectiveUntil(),
				null, source.currentValue(), source.activityCompatibility(), source.locationCompatibility(),
				source.timeCompatibility());
	}
}
