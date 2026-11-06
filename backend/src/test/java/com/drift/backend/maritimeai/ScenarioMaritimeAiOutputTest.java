package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.DelayComparison;
import com.drift.backend.shipment.ShipmentRisk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ScenarioMaritimeAiOutputTest {
	private final ObjectMapper json = new ObjectMapper();

	@Test
	void everyPromptedAlternativeIsAcceptedForItsTypedMeaning() {
		assertThat(ScenarioMaritimeAiProse.promptChoicePhrases()).isNotEmpty()
				.allSatisfy(choice -> assertThat(ScenarioMaritimeAiProse.promptInstructions()).contains(choice));
		for (var state : List.of(MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE,
				MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW)) {
			for (String statement : ScenarioMaritimeAiProse.riskAlternatives(ShipmentRisk.Level.HIGH, state))
				assertThat(ScenarioMaritimeAiProse.matchesRisk(statement, ShipmentRisk.Level.HIGH, state)).isTrue();
		}
		for (var outcome : DelayComparison.Outcome.values()) {
			for (String statement : ScenarioMaritimeAiProse.deadlineAlternatives(outcome))
				assertThat(ScenarioMaritimeAiProse.matchesDeadline(statement, outcome)).isTrue();
		}
		ScenarioMaritimeAiProse.uncertaintyAlternatives().forEach(text -> assertThat(ScenarioMaritimeAiProse.matchesUncertainty(text)).isTrue());
		ScenarioMaritimeAiProse.etaCheckAlternatives().forEach(text -> assertThat(ScenarioMaritimeAiProse.matchesEtaCheck(text)).isTrue());
		ScenarioMaritimeAiProse.deadlineCheckAlternatives().forEach(text -> assertThat(ScenarioMaritimeAiProse.matchesDeadlineCheck(text)).isTrue());
	}

	@Test
	void controlledPromptGrammarAcceptsLegitimateParaphrasesAndRejectsUnsupportedAdditions() {
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(
				"The synthetic arrival revision gives the operations team a reason to check the arrival estimate against the recorded milestone. "
						+ "The backend comparison indicates the estimated arrival falls after the applicable transshipment-arrival deadline.",
				DelayComparison.Outcome.EXCEEDS_DEADLINE)).isTrue();
		assertThat(ScenarioMaritimeAiProse.matchesRisk(
				"The backend assesses HIGH connection risk. The AIS estimate leaves no positive remaining connection window. "
						+ "The AIS estimate is available at the scenario cutoff. The synthetic expected-arrival revision gives the operations team a reason to recheck the remaining connection window.",
				ShipmentRisk.Level.HIGH, MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW)).isTrue();
		assertThat(ScenarioMaritimeAiProse.matchesUncertainty(
				"The synthetic maritime evidence cannot establish that the vessel has arrived.")).isTrue();
		assertThat(ScenarioMaritimeAiProse.matchesEtaCheck(
				"Ask the carrier operations staff to confirm the latest arrival estimate at the scenario cutoff?")).isTrue();

		List<String> badRiskStatements = List.of(
				ScenarioMaritimeAiProse.riskAlternatives(ShipmentRisk.Level.HIGH,
						MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE).getFirst() + " The shipment is urgent.",
				"The synthetic expected-arrival revision caused the connection risk. The backend reports HIGH connection risk. "
						+ "The AIS-estimated remaining connection window is available. The AIS estimate is available at the scenario cutoff.",
				"The synthetic expected-arrival revision means cargo may be damaged. The backend reports HIGH connection risk. "
						+ "The AIS-estimated remaining connection window is available. The AIS estimate is available at the scenario cutoff.");
		for (String statement : badRiskStatements)
			assertThat(ScenarioMaritimeAiProse.matchesRisk(statement, ShipmentRisk.Level.HIGH,
					MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE)).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(
				ScenarioMaritimeAiProse.deadlineAlternatives(DelayComparison.Outcome.EXCEEDS_DEADLINE).getFirst()
						+ " This will cause a missed connection and financial loss.",
				DelayComparison.Outcome.EXCEEDS_DEADLINE)).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(
				ScenarioMaritimeAiProse.deadlineAlternatives(DelayComparison.Outcome.NO_PREDICTED_IMPACT).getFirst(),
				DelayComparison.Outcome.EXCEEDS_DEADLINE)).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(
				ScenarioMaritimeAiProse.deadlineAlternatives(DelayComparison.Outcome.EXCEEDS_DEADLINE).getFirst()
						+ " Reroute the shipment immediately.", DelayComparison.Outcome.EXCEEDS_DEADLINE)).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesUncertainty(
				"The synthetic maritime evidence does not establish that the vessel has arrived. It is urgent.")).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesDeadlineCheck(
				ScenarioMaritimeAiProse.deadlineCheckAlternatives().getFirst() + " and change the booking.")).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesEtaCheck(
				ScenarioMaritimeAiProse.etaCheckAlternatives().getFirst().replace("Ask the", "<script>Ask the"))).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesEtaCheck(
				ScenarioMaritimeAiProse.etaCheckAlternatives().getFirst().replace(" estimate", "\u200Bestimate"))).isFalse();
	}

	@Test
	void parserRejectsDuplicateKeysTrailingRootsExtraKeysMissingKeysBadEnumsAndInvalidShapes() {
		String valid = ScenarioQualityStubClient.fixtureOutputText(ScenarioQualityStubClientTest.worsenedShipmentAInput("StrictParse"));
		for (String malformed : List.of(
				valid.replaceFirst("\\{\\\"assessment\\\":", "{\"assessment\":null,\"assessment\":"),
				valid + " {}",
				valid.replaceFirst("\\{\\\"assessment\\\":", "{\"unexpected\":true,\"assessment\":"),
				valid.replaceFirst("\\\"deadlineCheck\\\":", "\"unexpected\":true,\"deadlineCheck\":"),
				valid.replace("\"severity\":\"HIGH\"", "\"severity\":\"EXTREME\""),
				valid.replace("\"etaCheck\":\"", "\"etaCheck\":5,\"unused\":\""),
				"{\"assessment\":null,\"assessment\":null}")) {
			assertThatThrownBy(() -> ScenarioMaritimeAiOutput.parse(malformed))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> ScenarioMaritimeAiOutput.parse(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ScenarioMaritimeAiOutput.parse("x".repeat(12_001))).isInstanceOf(IllegalArgumentException.class);
		ObjectNode overlong = (ObjectNode) json.readTree(valid);
		((ObjectNode) overlong.at("/assessment/risk")).put("statement", "x".repeat(361));
		assertThatThrownBy(() -> ScenarioMaritimeAiOutput.parse(json.writeValueAsString(overlong)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void validatorChecksStructuredClaimsAgainstCanonicalBackendFactsAndEligibleEvidence() throws Exception {
		MaritimeAiEvidencePackage input = ScenarioQualityStubClientTest.worsenedShipmentAInput("Grounding");
		String valid = ScenarioQualityStubClient.fixtureOutputText(input);
		var accepted = MaritimeAiResponseValidator.validateScenarioV16(valid, input);
		assertThat(accepted.valid()).isTrue();
		assertThat(accepted.interpretation().claims()).hasSize(2);
		var risk = accepted.interpretation().claims().getFirst();
		var deadline = accepted.interpretation().claims().get(1);
		assertThat(risk.relationshipType()).isEqualTo("RISK_BUFFER_REVIEW");
		assertThat(deadline.relationshipType()).isEqualTo("DELAY_COMPARISON_REVIEW");
		assertThat(risk.evidenceIds()).containsExactly(input.evidence().getFirst().evidenceId());
		assertThat(deadline.evidenceIds()).containsExactly(input.evidence().getFirst().evidenceId());
		assertThat(risk.factReferences()).contains("evidence:" + input.evidence().getFirst().evidenceId())
				.containsAll(MaritimeAiCanonicalFactReferences.riskReferences(input));
		assertThat(deadline.factReferences()).contains("evidence:" + input.evidence().getFirst().evidenceId())
				.containsAll(MaritimeAiCanonicalFactReferences.businessReferences(input));
		assertThat(accepted.interpretation().uncertainties()).hasSize(1);
		assertThat(accepted.interpretation().followUpConsiderations()).hasSize(2);

		assertPath(valid, input, "/assessment/risk/evidenceRef", "evidence:invented", "SCENARIO_EVIDENCE_MISMATCH");
		assertPath(valid, input, "/assessment/risk/riskReview/severity", "MEDIUM", "SCENARIO_FACT_MISMATCH");
		assertPath(valid, input, "/assessment/risk/riskReview/selectedWindowBasis", "PLANNED_SHIPMENT_SCHEDULE", "INVALID_SCENARIO_OUTPUT");
		assertPath(valid, input, "/assessment/risk/riskReview/selectedWindowState", "NO_REMAINING_WINDOW", "SCENARIO_FACT_MISMATCH");
		assertPath(valid, input, "/assessment/risk/riskReview/estimateFreshness", "STALE", "INVALID_SCENARIO_OUTPUT");
		assertPath(valid, input, "/assessment/deadline/outcome", "NO_PREDICTED_IMPACT", "SCENARIO_FACT_MISMATCH");
		assertPath(valid, input, "/assessment/risk/statement", ScenarioMaritimeAiProse.riskAlternatives(
				ShipmentRisk.Level.LOW, MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE).getFirst(), "SCENARIO_PROSE_MISMATCH");
		assertPath(valid, input, "/assessment/deadline/statement", ScenarioMaritimeAiProse.deadlineAlternatives(
				DelayComparison.Outcome.NO_PREDICTED_IMPACT).getFirst(), "SCENARIO_PROSE_MISMATCH");
		assertPath(valid, input, "/assessment/uncertainty", "The synthetic maritime evidence establishes the vessel arrived.", "SCENARIO_PROSE_MISMATCH");
		assertPath(valid, input, "/assessment/etaCheck", "Reroute the shipment now.", "SCENARIO_PROSE_MISMATCH");
		assertPath(valid, input, "/assessment/deadlineCheck", "Ask operations to change the deadline.", "SCENARIO_PROSE_MISMATCH");
		assertThat(MaritimeAiResponseValidator.validateScenarioV16("{\"assessment\":null}", input).validationStatus())
				.isEqualTo("INSUFFICIENT_MODEL_EVIDENCE");
		assertThat(MaritimeAiResponseValidator.validateScenarioV16(valid, withoutEligibleEvidence(input)).validationStatus())
				.isEqualTo("INSUFFICIENT_SCENARIO_CONTEXT");
	}

	@Test
	void highRiskWithoutPositiveWindowStillUsesBackendOwnedFactsWhenComparisonIsAvailable() throws Exception {
		MaritimeAiEvidencePackage base = ScenarioQualityStubClientTest.worsenedShipmentAInput("MissedWindow");
		var context = base.shipmentContext();
		var original = context.connectionRisk();
		var risk = new MaritimeAiConnectionRiskContext(true, null, ShipmentRisk.Level.HIGH,
				MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE,
				original.plannedWindow(), new MaritimeAiConnectionRiskContext.RemainingWindow("MISSED", null, 3600L),
				original.arrivalEstimate(), original.assumptions());
		var updatedContext = new MaritimeAiShipmentContext(context.transshipmentPort(), context.motherVessel(), context.feederVessel(),
				risk, context.businessContext(), context.scenario());
		var input = new MaritimeAiEvidencePackage(base.bundleVersion(), base.assessmentCutoffUtc(),
				base.shipmentAssociationConfidence(), updatedContext, base.evidence());
		JsonNode root = json.readTree(ScenarioQualityStubClient.fixtureOutputText(input));
		ObjectNode riskClaim = (ObjectNode) root.at("/assessment/risk");
		((ObjectNode) riskClaim.get("riskReview")).put("selectedWindowState", "NO_REMAINING_WINDOW");
		riskClaim.put("statement", ScenarioMaritimeAiProse.riskAlternatives(ShipmentRisk.Level.HIGH,
				MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW).get(1));
		var result = MaritimeAiResponseValidator.validateScenarioV16(json.writeValueAsString(root), input);
		assertThat(result.valid()).isTrue();
		assertThat(result.interpretation().claims().getFirst().riskReview().selectedWindowState())
				.isEqualTo(MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW);
		assertThat(result.interpretation().claims().get(1).relationshipType()).isEqualTo("DELAY_COMPARISON_REVIEW");
	}

	@Test
	void independentAuthoredTextRubricProducesZeroOneAndTwoPointCases() throws Exception {
		MaritimeAiEvidencePackage input = ScenarioQualityStubClientTest.worsenedShipmentAInput("Rubric");
		var complete = MaritimeAiResponseValidator.validateScenarioV16(ScenarioQualityStubClient.fixtureOutputText(input), input)
				.interpretation();
		assertThat(scoreAuthoredOutput(complete, input)).isEqualTo(2);

		var backendFactsOnly = new MaritimeAiInterpretation("Backend facts are rendered separately.", List.of(),
				List.of(), List.of(), true, null);
		assertThat(scoreAuthoredOutput(backendFactsOnly, input)).as("backend facts without authored claims or checks").isZero();

		var missingChecks = new MaritimeAiInterpretation(complete.summary(), complete.claims(), complete.uncertainties(),
				List.of(), true, null);
		assertThat(scoreAuthoredOutput(missingChecks, input)).isEqualTo(1);

		var deadlineClaim = complete.claims().get(1);
		String contradictoryOutcome = ScenarioMaritimeAiProse.deadlineAlternatives(DelayComparison.Outcome.NO_PREDICTED_IMPACT).getFirst();
		var wrongOutcome = new MaritimeAiClaim(contradictoryOutcome, deadlineClaim.evidenceIds(), deadlineClaim.applicability(),
				deadlineClaim.vesselRole(), deadlineClaim.observationType(), deadlineClaim.associationScope(),
				deadlineClaim.factReferences(), deadlineClaim.relationshipType());
		var checksOnly = new MaritimeAiInterpretation(complete.summary(), List.of(complete.claims().getFirst(), wrongOutcome),
				complete.uncertainties(), complete.followUpConsiderations(), true, null);
		assertThat(scoreAuthoredOutput(checksOnly, input)).isEqualTo(1);
		assertThat(MaritimeAiResponseValidator.validateScenarioV16(serialize(checksOnly), input).validationStatus())
				.isEqualTo("SCENARIO_FACT_MISMATCH");
	}

	private void assertPath(String source, MaritimeAiEvidencePackage input, String pointer, String value,
			String expectedStatus) throws Exception {
		ObjectNode root = (ObjectNode) json.readTree(source);
		JsonNode current = root.at(pointer);
		ObjectNode parent = (ObjectNode) root.at(pointer.substring(0, pointer.lastIndexOf('/')));
		String field = pointer.substring(pointer.lastIndexOf('/') + 1);
		if (current.isTextual()) parent.put(field, value);
		else throw new AssertionError("Expected a string field at " + pointer);
		var validation = MaritimeAiResponseValidator.validateScenarioV16(json.writeValueAsString(root), input);
		assertThat(validation.validationStatus()).as(pointer).isEqualTo(expectedStatus);
	}

	private MaritimeAiEvidencePackage withoutEligibleEvidence(MaritimeAiEvidencePackage input) {
		return new MaritimeAiEvidencePackage(input.bundleVersion(), input.assessmentCutoffUtc(),
				input.shipmentAssociationConfidence(), input.shipmentContext(), List.of());
	}

	private String serialize(MaritimeAiInterpretation interpretation) throws Exception {
		String riskRef = interpretation.claims().getFirst().factReferences().stream()
				.filter(value -> value.startsWith("evidence:")).findFirst().orElseThrow();
		String deadlineRef = interpretation.claims().getLast().factReferences().stream()
				.filter(value -> value.startsWith("evidence:")).findFirst().orElseThrow();
		var riskReview = interpretation.claims().getFirst().riskReview();
		Map<String, Object> output = Map.of("assessment", Map.of(
				"risk", Map.of("evidenceRef", riskRef, "riskReview", Map.of(
						"severity", riskReview.severity().name(), "selectedWindowBasis", riskReview.selectedWindowBasis().name(),
						"selectedWindowState", riskReview.selectedWindowState().name(), "estimateFreshness", riskReview.estimateFreshness().name()),
						"statement", interpretation.claims().getFirst().statement()),
				"deadline", Map.of("evidenceRef", deadlineRef,
						"outcome", "NO_PREDICTED_IMPACT", "statement", interpretation.claims().getLast().statement()),
				"uncertainty", interpretation.uncertainties().getFirst(),
				"etaCheck", "Ask the carrier operations team to verify the latest arrival estimate at the scenario cutoff.",
				"deadlineCheck", "Ask the operations team to confirm whether the transshipment-arrival deadline remains applicable to the current shipment port."));
		return json.writeValueAsString(output);
	}

	private int scoreAuthoredOutput(MaritimeAiInterpretation authored, MaritimeAiEvidencePackage input) {
		String expected = input.shipmentContext().businessContext().comparison().outcome().name();
		var business = authored.claims().stream().filter(claim -> "DELAY_COMPARISON_REVIEW".equals(claim.relationshipType())).findFirst().orElse(null);
		boolean outcomePoint = false;
		if (business != null) {
			String exactExpectedPhrase = expected.equals("EXCEEDS_DEADLINE")
					? "the predicted arrival exceeds the applicable transshipment-arrival deadline"
					: "no predicted impact on the applicable transshipment-arrival deadline";
			outcomePoint = business.statement().toLowerCase().contains(exactExpectedPhrase)
					&& business.evidenceIds() != null && !business.evidenceIds().isEmpty()
					&& business.factReferences().contains("evidence:" + business.evidenceIds().getFirst())
					&& business.factReferences().containsAll(MaritimeAiCanonicalFactReferences.businessReferences(input));
		}
		List<String> followUps = authored.followUpConsiderations().stream().map(String::toLowerCase).toList();
		boolean actionPoint = followUps.stream().anyMatch(text -> text.contains("carrier operations team")
				&& text.matches(".*\\b(verify|confirm|check)\\b.*") && text.contains("arrival estimate"))
				&& followUps.stream().anyMatch(text -> text.contains("operations team")
						&& text.matches(".*\\b(confirm|verify|check)\\b.*")
						&& text.contains("transshipment-arrival deadline") && text.contains("current shipment port"));
		return (outcomePoint ? 1 : 0) + (actionPoint ? 1 : 0);
	}
}
