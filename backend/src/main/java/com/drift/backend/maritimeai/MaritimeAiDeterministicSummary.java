package com.drift.backend.maritimeai;

import java.util.List;

/** Backend-owned facts rendered beside, never overridden by, an AI interpretation. */
public record MaritimeAiDeterministicSummary(String summaryVersion, String label,
		List<Fact> shipmentScheduleFacts, List<Fact> evidenceFacts, List<String> limitations) {
	public record Fact(String factReference, String factType, String subject, String eventType, String timestamp,
			String previousValue, String currentValue, String evidenceId, String sourceType,
			String sourceMode, String associationScope, String citation, String observedAt, String retrievedAtUtc) { }
}
