package com.drift.backend.maritimechange;

import java.util.List;

/**
 * The candidate bundle for one evidence change, limited to shipments the viewer may see.
 * Exclusions are not part of the bundle.
 */
public record MaritimeShipmentMatch(
		long viewerCompanyId,
		String sourceType,
		String sourceIdentity,
		String contentFingerprint,
		List<MaritimeShipmentCandidate> candidates,
		List<MaritimeShipmentExclusion> exclusions) {
}
