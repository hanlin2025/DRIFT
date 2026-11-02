package com.drift.backend.maritimereassessment;

import java.time.Instant;
import java.util.List;

import com.drift.backend.maritimecontext.MaritimeEvidence;

/**
 * Evidence a caller wants considered for one reassessment.
 * Snapshot ids come from the caller when a change detector has them; otherwise the evidence ids are used.
 */
public record MaritimeEvidenceBundle(
		String datasetId,
		Instant cutoff,
		List<MaritimeEvidence> evidence,
		List<String> snapshotIds) {
}
