package com.drift.backend.maritimeai;

import java.util.Set;
import java.util.stream.Collectors;

import com.drift.backend.maritimecontext.MaritimeEvidence;

final class MaritimeAiResponseValidator {
	private MaritimeAiResponseValidator() { }
	static String validate(MaritimeAiInterpretation output, MaritimeAiEvidencePackage input) {
		if (output != null && output.formalRecommendationCode() != null) return "FORMAL_RECOMMENDATION_NOT_OWNED_BY_CDG247";
		if (output == null || output.summary() == null || output.summary().isBlank() || output.claims() == null
				|| output.uncertainties() == null || output.followUpConsiderations() == null
				|| (output.evidenceSufficient() && output.claims().isEmpty())) return "MALFORMED_OUTPUT";
		if (!"VERIFIED".equals(input.shipmentAssociationConfidence())
				&& referencesShipmentAsFact(output.summary(), "")) return "UNVERIFIED_SHIPMENT_ASSOCIATION";
		if (claimsCargoAsFact(output.summary())) return "UNSUPPORTED_CARGO_ASSERTION";
		if (claimsMissedConnection(output.summary())) return "UNSUPPORTED_MISSED_CONNECTION";
		if (claimsCausation(output.summary())) return "UNSUPPORTED_CAUSAL_CLAIM";
		Set<String> ids = input.evidence().stream().map(MaritimeEvidence::evidenceId).collect(Collectors.toSet());
		for (MaritimeAiClaim claim : output.claims()) {
			if (claim == null || claim.statement() == null || claim.statement().isBlank() || claim.evidenceIds() == null || claim.evidenceIds().isEmpty()) return "MISSING_CITATION";
			if (!ids.containsAll(claim.evidenceIds())) return "UNKNOWN_EVIDENCE_ID";
			if (claimsCausation(claim.statement())) return "UNSUPPORTED_CAUSAL_CLAIM";
			if (claimsMissedConnection(claim.statement())) return "UNSUPPORTED_MISSED_CONNECTION";
			if (claimsCargoAsFact(claim.statement())) return "UNSUPPORTED_CARGO_ASSERTION";
			for (String id : claim.evidenceIds()) {
				MaritimeEvidence source = input.evidence().stream().filter(item -> id.equals(item.evidenceId())).findFirst().orElseThrow();
				if ("INCOMPATIBLE".equals(source.compatibilityState()) || "UNAVAILABLE".equals(source.sourceMode())
						|| "STALE".equals(source.freshness()) || !source.evidenceSufficient()) return "INELIGIBLE_CITED_EVIDENCE";
			}
			if (!"VERIFIED".equals(input.shipmentAssociationConfidence())
					&& referencesShipmentAsFact(claim.statement(), claim.applicability())) {
				return "UNVERIFIED_SHIPMENT_ASSOCIATION";
			}
		}
		return null;
	}

	private static boolean referencesShipmentAsFact(String statementText, String applicabilityText) {
		String statement = statementText == null ? "" : statementText.toLowerCase();
		String applicability = applicabilityText == null ? "" : applicabilityText.toLowerCase();
		boolean marksUncertainty = statement.contains("unverified") || statement.contains("not verified")
				|| statement.contains("conditional") || statement.contains("if the association");
		return (!marksUncertainty && (statement.contains("shipment") || statement.contains("cargo")))
				|| applicability.contains("shipment_specific");
	}

	private static boolean claimsMissedConnection(String text) {
		String statement = text == null ? "" : text.toLowerCase();
		return statement.contains("missed feeder connection") || statement.contains("feeder connection was missed")
				|| statement.contains("connection has been missed") || statement.contains("will miss the connection");
	}

	private static boolean claimsCausation(String text) {
		String statement = text == null ? "" : text.toLowerCase();
		return statement.contains(" caused ") || statement.contains("caused by");
	}

	private static boolean claimsCargoAsFact(String text) {
		String statement = text == null ? "" : text.toLowerCase();
		return statement.contains("cargo") && !statement.contains("no cargo")
				&& !statement.contains("cargo association") && !statement.contains("cargo is not");
	}
}
