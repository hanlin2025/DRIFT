package com.drift.backend.maritimechange;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projection of current persisted observations for evidence adapters. */
@Service
public class MaritimeSnapshotEvidenceReader {
	private final MaritimeSnapshotObservationStore store;

	public MaritimeSnapshotEvidenceReader(MaritimeSnapshotObservationStore store) {
		this.store = store;
	}

	@Transactional(readOnly = true)
	public List<Snapshot> heads(String sourceType) {
		return store.heads(sourceType).stream().map(row -> new Snapshot(
				row.getSourceType(), row.getSourceIdentity(), row.getContentFingerprint(), row.getProvenance(), row.getSourceEventAt(),
				row.getObservedAt(), row.getRetrievedAtUtc(), row.getSourceMode(), row.getFreshness(),
				row.getLocation(), row.getEffectiveFrom(), row.getEffectiveUntil(), row.getPreviousValue(),
				row.getCurrentValue(), row.getActivityCompatibility(), row.getLocationCompatibility(),
				row.getTimeCompatibility())).toList();
	}

	public record Snapshot(String sourceType, String sourceIdentity, String contentFingerprint, String provenance, String sourceEventAt,
			String observedAt, Instant retrievedAtUtc, String sourceMode, String freshness, String location,
			Instant effectiveFrom, Instant effectiveUntil, String previousValue, String currentValue,
			String activityCompatibility, String locationCompatibility, String timeCompatibility) { }
}
