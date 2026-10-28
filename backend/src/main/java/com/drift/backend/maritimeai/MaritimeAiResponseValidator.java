package com.drift.backend.maritimeai;

import java.util.Set;
import java.util.stream.Collectors;

import com.drift.backend.maritimecontext.MaritimeEvidence;

final class MaritimeAiResponseValidator {
	private MaritimeAiResponseValidator() { }
	static String validate(MaritimeAiInterpretation output, MaritimeAiEvidencePackage input) {
		if (output == null || output.summary() == null || output.summary().isBlank() || output.claims() == null) return "MALFORMED_OUTPUT";
		if (output.formalRecommendationCode() != null) return "FORMAL_RECOMMENDATION_NOT_OWNED_BY_CDG247";
		Set<String> ids = input.evidence().stream().map(MaritimeEvidence::evidenceId).collect(Collectors.toSet());
		for (MaritimeAiClaim claim : output.claims()) {
			if (claim == null || claim.statement() == null || claim.statement().isBlank() || claim.evidenceIds() == null || claim.evidenceIds().isEmpty()) return "MISSING_CITATION";
			if (!ids.containsAll(claim.evidenceIds())) return "UNKNOWN_EVIDENCE_ID";
			String normalized = claim.statement().toLowerCase();
			if (normalized.contains(" caused ") || normalized.contains("caused by")) return "UNSUPPORTED_CAUSAL_CLAIM";
			for (String id : claim.evidenceIds()) {
				MaritimeEvidence source = input.evidence().stream().filter(item -> id.equals(item.evidenceId())).findFirst().orElseThrow();
				if ("INCOMPATIBLE".equals(source.compatibilityState()) || "UNAVAILABLE".equals(source.sourceMode())) return "INELIGIBLE_CITED_EVIDENCE";
			}
		}
		return null;
	}
}
