package com.drift.backend.maritimecontext;

import java.util.List;

/**
 * Read-only maritime notice context. This contract deliberately does not claim that a notice
 * disrupted this shipment, changed an ETA, or warrants a risk decision.
 */
public record MaritimeContextResponse(
		String contractVersion,
		boolean enabled,
		String sourceMode,
		String assessmentCutoffUtc,
		String shipmentAssociationConfidence,
		String limitation,
		List<MaritimeEvidence> evidence,
		List<MaritimeNoticeEvidence> notices,
		Object aiExplanation,
		Object suggestedActions) {
}
