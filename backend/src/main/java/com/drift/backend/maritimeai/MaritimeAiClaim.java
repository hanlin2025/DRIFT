package com.drift.backend.maritimeai;

import java.util.List;

import com.drift.backend.shipment.ShipmentRisk;

/** A model claim is displayable only after each evidence id is validated against the input bundle. */
public record MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability,
		String vesselRole, String observationType, String associationScope, List<String> factReferences,
		String relationshipType, RiskReview riskReview) {
	/** Typed backend-risk values paired with a risk-review claim; never an evidence citation. */
	public record RiskReview(ShipmentRisk.Level severity, MaritimeAiConnectionRiskContext.RiskBasis selectedWindowBasis,
			SelectedWindowState selectedWindowState, MaritimeAiConnectionRiskContext.EstimateState estimateFreshness) {
		/** MISSED in the shipment calculator means the estimate leaves no positive window; it is not an observed missed connection. */
		public enum SelectedWindowState { AVAILABLE, NO_REMAINING_WINDOW, INVALID, INCOMPLETE }
	}
	/** Preserves source compatibility for existing callers and old persisted interpretations. */
	public MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability,
			String vesselRole, String observationType, String associationScope, List<String> factReferences,
			String relationshipType) {
		this(statement, evidenceIds, applicability, vesselRole, observationType, associationScope, factReferences, relationshipType, null);
	}
	/** Existing stored/test output remains readable; enriched source facts require the new fields. */
	public MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability) {
		this(statement, evidenceIds, applicability, null, null, null, List.of(), null, null);
	}
	public MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability,
			String vesselRole, String observationType, String associationScope) {
		this(statement, evidenceIds, applicability, vesselRole, observationType, associationScope, List.of(), null, null);
	}
	public MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability,
			String vesselRole, String observationType, String associationScope, List<String> factReferences) {
		this(statement, evidenceIds, applicability, vesselRole, observationType, associationScope, factReferences, null, null);
	}
}
