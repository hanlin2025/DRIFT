package com.drift.backend.maritimeai;

import java.util.List;

/**
 * Shared two-point usefulness rubric: a cited AI-authored deadline direction and exact numerical margin
 * that match the typed comparison, plus two concrete human checks. Backend facts remain authoritative.
 */
final class ScenarioAiUsefulnessRubric {
	private ScenarioAiUsefulnessRubric() { }

	static Score evaluate(MaritimeAiInterpretation authored, MaritimeAiEvidencePackage input) {
		if (authored == null || input == null || input.shipmentContext() == null
				|| input.shipmentContext().scenario() == null
				|| input.shipmentContext().businessContext() == null
				|| input.shipmentContext().businessContext().comparison() == null
				|| authored.claims() == null || authored.followUpConsiderations() == null)
			return new Score(0, 0);
		var comparison = input.shipmentContext().businessContext().comparison();
		List<String> eligibleEvidenceIds = ScenarioMaritimeAiOutput.eligibleRevisionChoices(input).stream()
				.map(com.drift.backend.maritimecontext.MaritimeEvidence::evidenceId).toList();
		List<MaritimeAiClaim> businessClaims = authored.claims().stream()
				.filter(claim -> claim != null && "DELAY_COMPARISON_REVIEW".equals(claim.relationshipType())).toList();
		boolean groundedOutcome = false;
		if (businessClaims.size() == 1) {
			MaritimeAiClaim business = businessClaims.getFirst();
			List<String> evidenceIds = business.evidenceIds();
			List<String> factReferences = business.factReferences();
			boolean eligibleCitation = evidenceIds != null && !evidenceIds.isEmpty()
					&& eligibleEvidenceIds.containsAll(evidenceIds);
			boolean evidenceReferences = eligibleCitation && factReferences != null
					&& factReferences.contains("evidence:" + evidenceIds.getFirst());
			ScenarioDeadlineImpact impact = ScenarioDeadlineImpact.from(comparison);
			groundedOutcome = impact != null && ScenarioMaritimeAiProse.matchesDeadline(business.statement(),
					input.shipmentContext().scenario().shipmentKey(), comparison)
					&& business.statement().toLowerCase(java.util.Locale.ROOT).contains(impact.magnitude().toLowerCase(java.util.Locale.ROOT))
					&& evidenceReferences
					&& factReferences.containsAll(MaritimeAiCanonicalFactReferences.businessReferences(input));
		}
		List<String> followUps = authored.followUpConsiderations().stream().filter(java.util.Objects::nonNull).toList();
		boolean humanChecks = followUps.stream().anyMatch(ScenarioMaritimeAiProse::matchesEtaCheck)
				&& followUps.stream().anyMatch(ScenarioMaritimeAiProse::matchesDeadlineCheck);
		return new Score(groundedOutcome ? 1 : 0, humanChecks ? 1 : 0);
	}

	record Score(int groundedOutcome, int humanVerificationChecks) {
		int total() { return groundedOutcome + humanVerificationChecks; }
	}
}
