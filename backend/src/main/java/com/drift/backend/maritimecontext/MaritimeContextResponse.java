package com.drift.backend.maritimecontext;

import java.util.List;

/**
 * Read-only maritime notice context. This contract deliberately does not claim that a notice
 * disrupted this shipment, changed an ETA, or warrants a risk decision.
 */
public record MaritimeContextResponse(
		boolean enabled,
		String sourceMode,
		String shipmentAssociationConfidence,
		String limitation,
		List<MaritimeNoticeEvidence> notices,
		Object aiExplanation,
		Object suggestedActions) {
}
