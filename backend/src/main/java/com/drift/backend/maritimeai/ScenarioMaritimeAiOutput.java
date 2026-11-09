package com.drift.backend.maritimeai;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.shipment.DelayComparison;
import com.drift.backend.shipment.ShipmentRisk;
import com.drift.backend.shipment.ShipmentRisk.Level;

import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Transient v1.6 provider wire shape and the backend-owned scenario projection. */
record ScenarioMaritimeAiOutput(Assessment assessment) {
	private static final JsonFactory STRICT_JSON_FACTORY = JsonFactory.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
	private static final ObjectMapper STRICT_JSON = new ObjectMapper(STRICT_JSON_FACTORY);

	record Assessment(RiskClaim risk, DeadlineClaim deadline, String uncertainty, String etaCheck, String deadlineCheck) { }
	record RiskClaim(String evidenceRef, RiskFacts riskReview, String statement) { }
	record RiskFacts(String severity, String selectedWindowBasis, String selectedWindowState, String estimateFreshness) { }
	record DeadlineClaim(String evidenceRef, String outcome, String statement) { }
	record RevisionChoice(String evidenceRef, String sourceMode, String sourceType, String vesselRole,
			String observationType, String associationScope, String previousExpectedArrival,
			String currentExpectedArrival, String observedAt, String retrievedAtUtc) { }
	record RiskPromptFacts(String severity, String selectedWindowBasis, String selectedWindowState,
			String estimateFreshness, String estimatedArrivalAt, String positionReceivedAt, Long remainingSeconds) { }
	record DeadlinePromptFacts(String state, String deadlineAt, String recordedTransshipmentPort,
			Boolean applicableToCurrentPort) { }
	record ComparisonPromptFacts(String state, String outcome, String estimatedArrivalAt,
			String comparedDeadline, String recordedTransshipmentPort) { }
	record PromptData(String stage, String cutoffUtc, String scenarioShipmentKey, String currentTransshipmentPort, RiskPromptFacts risk,
			DeadlinePromptFacts deadline, ComparisonPromptFacts comparison, List<RevisionChoice> eligibleRevisions) { }

	/** Returns a safe field path when a positive-stage v1.6 request is missing required context. */
	static String preflightFailureField(MaritimeAiEvidencePackage input) {
		if (input == null || input.shipmentContext() == null || input.shipmentContext().scenario() == null)
			return "scenario";
		var scenario = input.shipmentContext().scenario();
		if (!List.of("CHANGE", "WORSENED").contains(scenario.stage())) return "scenario.stage";
		Instant cutoff;
		try {
			cutoff = Instant.parse(input.assessmentCutoffUtc());
			if (!cutoff.toString().equals(input.assessmentCutoffUtc())
					|| !scenario.simulationCutoffUtc().equals(input.assessmentCutoffUtc())) return "scenario.cutoff";
		} catch (RuntimeException invalid) { return "scenario.cutoff"; }
		if (!"VERIFIED".equals(input.shipmentAssociationConfidence())) return "shipmentAssociationConfidence";

		List<MaritimeEvidence> revisions = eligibleRevisionChoices(input);
		if (revisions.isEmpty()) return "eligibleRevisions";
		for (MaritimeEvidence revision : revisions) {
			long duplicateReferences = input.evidence().stream()
					.filter(candidate -> candidate != null && candidate.evidenceId().equals(revision.evidenceId())).count();
			if (duplicateReferences != 1) return "eligibleRevisions";
			if (!validBeforeCutoff(revision.observedAt(), cutoff) || !validBeforeCutoff(revision.retrievedAtUtc(), cutoff)
					|| !validOffsetDateTime(revision.previousValue()) || !validOffsetDateTime(revision.currentValue()))
				return "eligibleRevisions";
		}

		MaritimeAiConnectionRiskContext risk = input.shipmentContext().connectionRisk();
		if (!completeAisRisk(risk, cutoff)) return "shipmentContext.connectionRisk";
		MaritimeAiClaim.RiskReview review = MaritimeAiResponseValidator.expectedRiskReview(risk);
		if (review.selectedWindowState() != MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE
				&& review.selectedWindowState() != MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW)
			return "shipmentContext.connectionRisk.remainingWindow";

		MaritimeAiBusinessContext business = input.shipmentContext().businessContext();
		if (!completeComparison(business, risk, input.shipmentContext().transshipmentPort())) return "shipmentContext.businessContext";
		List<String> requiredRisk = MaritimeAiCanonicalFactReferences.riskReferences(input);
		List<String> requiredBusiness = List.of(MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE,
				MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_PORT,
				MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_APPLICABILITY,
				MaritimeAiCanonicalFactReferences.COMPARISON_OUTCOME,
				MaritimeAiCanonicalFactReferences.COMPARISON_ESTIMATED_ARRIVAL,
				MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_DEADLINE,
				MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_PORT);
		var allowed = MaritimeAiCanonicalFactReferences.allowed(input);
		if (!allowed.containsAll(requiredRisk)) return "shipmentContext.connectionRisk";
		if (!allowed.containsAll(requiredBusiness)) return "shipmentContext.businessContext";
		return null;
	}

	static List<MaritimeEvidence> eligibleRevisionChoices(MaritimeAiEvidencePackage input) {
		if (input == null || input.evidence() == null) return List.of();
		Instant cutoff;
		try { cutoff = Instant.parse(input.assessmentCutoffUtc()); }
		catch (RuntimeException invalidCutoff) { return List.of(); }
		return input.evidence().stream().filter(item -> item != null
				&& "SYNTHETIC_DEMONSTRATION".equals(item.sourceMode())
				&& "MPA_ETA_REVISION".equals(item.sourceType())
				&& "MOTHER".equals(item.vesselRole())
				&& "EXPECTED_ARRIVAL".equals(item.observationType())
				&& "VESSEL_OBSERVATION".equals(item.associationScope())
				&& "FRESH".equals(item.freshness()) && "COMPATIBLE".equals(item.compatibilityState())
				&& "COMPATIBLE".equals(item.activityCompatibility())
				&& "COMPATIBLE".equals(item.locationCompatibility())
				&& "COMPATIBLE".equals(item.timeCompatibility())
				&& item.evidenceSufficient() && "VERIFIED".equals(item.shipmentAssociationConfidence())
				&& nonblank(item.evidenceId()) && nonblank(item.previousValue()) && nonblank(item.currentValue())
				&& nonblank(item.observedAt()) && nonblank(item.retrievedAtUtc())
				&& validBeforeCutoff(item.observedAt(), cutoff) && validBeforeCutoff(item.retrievedAtUtc(), cutoff)
				&& validOffsetDateTime(item.previousValue()) && validOffsetDateTime(item.currentValue())).toList();
	}

	static ScenarioMaritimeAiOutput parse(String text) {
		if (text == null || text.isBlank() || text.length() > 12_000) throw invalid();
		try (JsonParser parser = STRICT_JSON_FACTORY.createParser(text)) {
			JsonNode root = STRICT_JSON.readTree(parser);
			if (root == null || parser.nextToken() != null) throw invalid();
			requireFields(root, "assessment");
			JsonNode assessment = root.get("assessment");
			if (assessment.isNull()) return new ScenarioMaritimeAiOutput(null);
			requireFields(assessment, "risk", "deadline", "uncertainty", "etaCheck", "deadlineCheck");
			JsonNode risk = assessment.get("risk");
			requireFields(risk, "evidenceRef", "riskReview", "statement");
			JsonNode riskReview = risk.get("riskReview");
			requireFields(riskReview, "severity", "selectedWindowBasis", "selectedWindowState", "estimateFreshness");
			JsonNode deadline = assessment.get("deadline");
			requireFields(deadline, "evidenceRef", "outcome", "statement");
			String severity = choice(riskReview, "severity", 32, "LOW", "MEDIUM", "HIGH", "CRITICAL");
			String basis = choice(riskReview, "selectedWindowBasis", 64, "AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE");
			String window = choice(riskReview, "selectedWindowState", 40, "AVAILABLE", "NO_REMAINING_WINDOW");
			String freshness = choice(riskReview, "estimateFreshness", 32, "AVAILABLE");
			String outcome = choice(deadline, "outcome", 40, "EXCEEDS_DEADLINE", "NO_PREDICTED_IMPACT");
			RiskClaim riskClaim = new RiskClaim(string(risk, "evidenceRef", 160),
					new RiskFacts(severity, basis, window, freshness),
					string(risk, "statement", 360));
			DeadlineClaim deadlineClaim = new DeadlineClaim(string(deadline, "evidenceRef", 160),
					outcome, string(deadline, "statement", 360));
			return new ScenarioMaritimeAiOutput(new Assessment(riskClaim, deadlineClaim,
					string(assessment, "uncertainty", 200), string(assessment, "etaCheck", 200),
					string(assessment, "deadlineCheck", 200)));
		} catch (RuntimeException failure) {
			throw invalid();
		}
	}

	static PromptData promptData(MaritimeAiEvidencePackage input) {
		var context = input.shipmentContext();
		var scenario = context.scenario();
		var risk = context.connectionRisk();
		var review = MaritimeAiResponseValidator.expectedRiskReview(risk);
		var estimate = risk.arrivalEstimate();
		var window = risk.remainingWindow();
		var business = context.businessContext();
		var deadline = business.deadline();
		var comparison = business.comparison();
		List<RevisionChoice> choices = eligibleRevisionChoices(input).stream().map(item -> new RevisionChoice(
				"evidence:" + item.evidenceId(), item.sourceMode(), item.sourceType(), item.vesselRole(),
				item.observationType(), item.associationScope(), item.previousValue(), item.currentValue(),
				item.observedAt(), item.retrievedAtUtc())).toList();
		return new PromptData(scenario.stage(), input.assessmentCutoffUtc(), scenario.shipmentKey(), context.transshipmentPort(),
				new RiskPromptFacts(review.severity().name(), review.selectedWindowBasis().name(),
						review.selectedWindowState().name(), review.estimateFreshness().name(),
						estimate.estimatedArrivalAt().toString(), estimate.positionReceivedAt().toString(),
						window.remainingSeconds()),
				new DeadlinePromptFacts(deadline.state().name(), deadline.instant().toString(),
					deadline.recordedTransshipmentPort(), deadline.applicableToCurrentPort()),
				new ComparisonPromptFacts(comparison.state().name(), comparison.outcome().name(),
					comparison.estimatedArrivalAt().toString(), comparison.comparedDeadline().toString(),
					comparison.recordedTransshipmentPort()), choices);
	}

	static MaritimeAiInterpretation project(ScenarioMaritimeAiOutput output, MaritimeAiEvidencePackage input) {
		Assessment assessment = Objects.requireNonNull(output.assessment());
		MaritimeEvidence riskEvidence = resolve(assessment.risk().evidenceRef(), input);
		MaritimeEvidence deadlineEvidence = resolve(assessment.deadline().evidenceRef(), input);
		var risk = input.shipmentContext().connectionRisk();
		List<String> riskRefs = new ArrayList<>();
		riskRefs.add(assessment.risk().evidenceRef());
		riskRefs.addAll(MaritimeAiCanonicalFactReferences.riskReferences(input));
		List<String> deadlineRefs = new ArrayList<>();
		deadlineRefs.add(assessment.deadline().evidenceRef());
		deadlineRefs.addAll(List.of(MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE,
				MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_PORT,
				MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_APPLICABILITY,
				MaritimeAiCanonicalFactReferences.COMPARISON_OUTCOME,
				MaritimeAiCanonicalFactReferences.COMPARISON_ESTIMATED_ARRIVAL,
				MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_DEADLINE,
				MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_PORT));
		MaritimeAiClaim riskClaim = new MaritimeAiClaim(assessment.risk().statement(), List.of(riskEvidence.evidenceId()),
				"CONTEXTUAL", riskEvidence.vesselRole(), riskEvidence.observationType(), riskEvidence.associationScope(),
				riskRefs, "RISK_BUFFER_REVIEW", MaritimeAiResponseValidator.expectedRiskReview(risk));
		MaritimeAiClaim deadlineClaim = new MaritimeAiClaim(assessment.deadline().statement(),
				List.of(deadlineEvidence.evidenceId()), "CONTEXTUAL", deadlineEvidence.vesselRole(),
				deadlineEvidence.observationType(), deadlineEvidence.associationScope(), deadlineRefs,
				"DELAY_COMPARISON_REVIEW");
		return new MaritimeAiInterpretation("Review the grounded interpretation and limitations.",
				List.of(riskClaim, deadlineClaim), List.of(assessment.uncertainty()),
				List.of(assessment.etaCheck(), assessment.deadlineCheck()), true, null);
	}

	private static boolean completeAisRisk(MaritimeAiConnectionRiskContext risk, Instant cutoff) {
		if (risk == null || !risk.assessed() || risk.riskLevel() == null
				|| risk.riskBasis() != MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE
				|| risk.remainingWindow() == null || risk.arrivalEstimate() == null
				|| risk.arrivalEstimate().state() != MaritimeAiConnectionRiskContext.EstimateState.AVAILABLE
				|| risk.arrivalEstimate().estimatedArrivalAt() == null || risk.arrivalEstimate().positionReceivedAt() == null
				|| risk.arrivalEstimate().unavailableReason() != null)
			return false;
		if (risk.arrivalEstimate().positionReceivedAt().isAfter(cutoff)) return false;
		if ("AVAILABLE".equals(risk.remainingWindow().status()))
			return risk.remainingWindow().remainingSeconds() != null && risk.remainingWindow().remainingSeconds() > 0;
		return "MISSED".equals(risk.remainingWindow().status())
				&& risk.remainingWindow().missedBySeconds() != null && risk.remainingWindow().missedBySeconds() > 0;
	}

	private static boolean completeComparison(MaritimeAiBusinessContext business, MaritimeAiConnectionRiskContext risk,
			String currentTransshipmentPort) {
		if (business == null || business.deadline() == null || business.comparison() == null) return false;
		var deadline = business.deadline();
		var comparison = business.comparison();
		if (deadline.state() != MaritimeAiBusinessContext.DeadlineState.RECORDED || deadline.instant() == null
				|| !nonblank(deadline.recordedTransshipmentPort()) || !Boolean.TRUE.equals(deadline.applicableToCurrentPort())
				|| comparison.state() != MaritimeAiBusinessContext.ComparisonState.AVAILABLE || comparison.outcome() == null
				|| comparison.unavailableReason() != null || comparison.estimatedArrivalAt() == null
				|| comparison.comparedDeadline() == null || !nonblank(comparison.recordedTransshipmentPort())) return false;
		if (!deadline.instant().toInstant().equals(comparison.comparedDeadline().toInstant())
				|| !deadline.recordedTransshipmentPort().equals(comparison.recordedTransshipmentPort())
				|| !nonblank(currentTransshipmentPort)
				|| !deadline.recordedTransshipmentPort().equalsIgnoreCase(currentTransshipmentPort)) return false;
		return risk.arrivalEstimate().estimatedArrivalAt().toInstant().equals(comparison.estimatedArrivalAt().toInstant());
	}

	private static boolean validBeforeCutoff(String value, Instant cutoff) {
		try { return OffsetDateTime.parse(value).toInstant().compareTo(cutoff) <= 0; }
		catch (RuntimeException invalid) { return false; }
	}
	private static boolean validOffsetDateTime(String value) {
		try { OffsetDateTime.parse(value); return true; }
		catch (RuntimeException invalid) { return false; }
	}
	private static boolean nonblank(String value) { return value != null && !value.isBlank(); }

	private static MaritimeEvidence resolve(String reference, MaritimeAiEvidencePackage input) {
		return eligibleRevisionChoices(input).stream().filter(item -> ("evidence:" + item.evidenceId()).equals(reference))
				.findFirst().orElseThrow(ScenarioMaritimeAiOutput::invalid);
	}

	private static void requireFields(JsonNode node, String... fields) {
		if (node == null || !node.isObject() || node.size() != fields.length) throw invalid();
		for (String field : fields) if (!node.has(field)) throw invalid();
	}

	private static String string(JsonNode node, String field, int maxLength) {
		JsonNode value = node.get(field);
		if (value == null || !value.isString()) throw invalid();
		String text = value.asText();
		if (text.isBlank() || text.length() > maxLength) throw invalid();
		return text;
	}

	private static String choice(JsonNode node, String field, int maxLength, String... allowed) {
		String value = string(node, field, maxLength);
		for (String candidate : allowed) if (candidate.equals(value)) return value;
		throw invalid();
	}

	private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid controlled-scenario output."); }
}
