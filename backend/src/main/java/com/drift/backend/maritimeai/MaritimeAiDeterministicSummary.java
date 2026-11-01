package com.drift.backend.maritimeai;

import java.util.List;
import com.drift.backend.shipment.ShipmentRisk;

/** Backend-owned facts rendered beside, never overridden by, an AI interpretation. */
public record MaritimeAiDeterministicSummary(String summaryVersion, String label,
		List<Fact> shipmentScheduleFacts, List<Fact> evidenceFacts, List<String> limitations,
		RiskSnapshot connectionRisk) {
	/** Historical summaries created before CDG-259 continue to deserialize without a risk snapshot. */
	public MaritimeAiDeterministicSummary(String summaryVersion, String label, List<Fact> shipmentScheduleFacts,
			List<Fact> evidenceFacts, List<String> limitations) {
		this(summaryVersion, label, shipmentScheduleFacts, evidenceFacts, limitations, null);
	}
	public record RiskSnapshot(boolean assessed, ShipmentRisk.Level severity,
			MaritimeAiConnectionRiskContext.RiskBasis selectedWindowBasis, SelectedWindowState selectedWindowState,
			Long windowSeconds, Long missedBySeconds, MaritimeAiConnectionRiskContext.EstimateState estimateFreshness,
			String estimateUnavailableCode, String unavailableReason, List<String> calculationLimitations,
			List<String> canonicalFactReferences) {
		public enum SelectedWindowState { AVAILABLE, MISSED, INVALID, INCOMPLETE }
		public RiskSnapshot {
			calculationLimitations = calculationLimitations == null ? List.of() : List.copyOf(calculationLimitations);
			canonicalFactReferences = canonicalFactReferences == null ? List.of() : List.copyOf(canonicalFactReferences);
		}
		static RiskSnapshot from(MaritimeAiConnectionRiskContext risk, List<String> factReferences) {
			if (risk == null) return new RiskSnapshot(false, null, null, SelectedWindowState.INCOMPLETE, null, null,
					MaritimeAiConnectionRiskContext.EstimateState.UNKNOWN, null, "RISK_CONTEXT_UNAVAILABLE", List.of(), List.of());
			SelectedWindowState state = SelectedWindowState.INCOMPLETE;
			Long seconds = null;
			Long missedBySeconds = null;
			if (risk.assessed() && risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE) {
				if (risk.remainingWindow() != null) {
					state = "AVAILABLE".equals(risk.remainingWindow().status()) ? SelectedWindowState.AVAILABLE : SelectedWindowState.MISSED;
					if (state == SelectedWindowState.AVAILABLE) seconds = risk.remainingWindow().remainingSeconds();
					if (state == SelectedWindowState.MISSED) missedBySeconds = risk.remainingWindow().missedBySeconds();
				}
			} else if (risk.assessed() && risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE
					&& risk.plannedWindow() != null) {
				state = switch (risk.plannedWindow().state()) {
					case AVAILABLE -> SelectedWindowState.AVAILABLE;
					case INVALID -> SelectedWindowState.INVALID;
					case INCOMPLETE -> SelectedWindowState.INCOMPLETE;
				};
				if (state == SelectedWindowState.AVAILABLE) seconds = risk.plannedWindow().durationSeconds();
			}
			return new RiskSnapshot(risk.assessed(), risk.riskLevel(), risk.riskBasis(), state, seconds, missedBySeconds,
					risk.arrivalEstimate() == null ? MaritimeAiConnectionRiskContext.EstimateState.UNKNOWN : risk.arrivalEstimate().state(),
					risk.arrivalEstimate() == null ? null : risk.arrivalEstimate().unavailableReason(), risk.unavailableReason(),
					risk.assumptions(), factReferences);
		}
	}
	public record Fact(String factReference, String factType, String subject, String eventType, String timestamp,
			String previousValue, String currentValue, String evidenceId, String sourceType,
			String sourceMode, String associationScope, String citation, String observedAt, String retrievedAtUtc) { }
}
