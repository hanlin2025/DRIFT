package com.drift.backend.maritimeai;

import java.util.List;

/** A model claim is displayable only after each evidence id is validated against the input bundle. */
public record MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability,
		String vesselRole, String observationType, String associationScope, List<String> factReferences,
		String relationshipType) {
	/** Existing stored/test output remains readable; enriched source facts require the new fields. */
	public MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability) {
		this(statement, evidenceIds, applicability, null, null, null, List.of(), null);
	}
	public MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability,
			String vesselRole, String observationType, String associationScope) {
		this(statement, evidenceIds, applicability, vesselRole, observationType, associationScope, List.of(), null);
	}
	public MaritimeAiClaim(String statement, List<String> evidenceIds, String applicability,
			String vesselRole, String observationType, String associationScope, List<String> factReferences) {
		this(statement, evidenceIds, applicability, vesselRole, observationType, associationScope, factReferences, null);
	}
}
