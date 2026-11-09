package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.drift.backend.shipment.DelayComparison;
import com.drift.backend.shipment.ShipmentRisk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ScenarioMaritimeAiOutputTest {
	private final ObjectMapper json = new ObjectMapper();

	@Test
	void capturedGenuineWorsenedAResponseReplaysAsValidWithItsBackendVerifiedDeadlineDirection() throws Exception {
		MaritimeAiEvidencePackage input = ScenarioQualityStubClientTest.worsenedShipmentAInput("GenuineReplay");
		String captured = genuineWorsenedAResponse();
		ScenarioMaritimeAiOutput parsed = ScenarioMaritimeAiOutput.parse(captured);
		assertThat(parsed.assessment().risk().riskReview().severity()).isEqualTo("HIGH");
		assertThat(parsed.assessment().risk().riskReview().selectedWindowBasis())
				.isEqualTo("AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE");
		assertThat(parsed.assessment().risk().riskReview().selectedWindowState()).isEqualTo("AVAILABLE");
		assertThat(parsed.assessment().risk().riskReview().estimateFreshness()).isEqualTo("AVAILABLE");
		assertThat(parsed.assessment().deadline().outcome()).isEqualTo("EXCEEDS_DEADLINE");
		assertThat(parsed.assessment().risk().evidenceRef()).isEqualTo("evidence:cdg279-v1-revision-1");
		assertThat(parsed.assessment().deadline().evidenceRef()).isEqualTo("evidence:cdg279-v1-revision-1");
		assertThat(input.shipmentContext().connectionRisk().remainingWindow().remainingSeconds()).isEqualTo(14_400L);
		assertThat(input.shipmentContext().businessContext().comparison().outcome())
				.isEqualTo(DelayComparison.Outcome.EXCEEDS_DEADLINE);
		assertThat(java.time.Duration.between(
				input.shipmentContext().businessContext().comparison().comparedDeadline(),
				input.shipmentContext().businessContext().comparison().estimatedArrivalAt()).toHours()).isEqualTo(20);

		var validation = MaritimeAiResponseValidator.validateScenarioV16(captured, input);
		assertThat(validation.valid()).isTrue();
		assertThat(validation.validationStatus()).isEqualTo("VALID");
		var authored = ScenarioMaritimeAiOutput.project(parsed, input);
		var risk = authored.claims().getFirst();
		var deadline = authored.claims().get(1);
		assertThat(risk.evidenceIds()).containsExactly("cdg279-v1-revision-1");
		assertThat(deadline.evidenceIds()).containsExactly("cdg279-v1-revision-1");
		assertThat(risk.riskReview().severity()).isEqualTo(ShipmentRisk.Level.HIGH);
		assertThat(risk.riskReview().selectedWindowState())
				.isEqualTo(MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE);
		assertThat(risk.factReferences()).containsAll(MaritimeAiCanonicalFactReferences.riskReferences(input));
		assertThat(deadline.factReferences()).containsAll(MaritimeAiCanonicalFactReferences.businessReferences(input));
		assertThat(deadline.statement()).contains("shows estimated arrival exceeds the applicable transshipment-arrival deadline");

		var usefulness = ScenarioAiUsefulnessRubric.evaluate(authored, input);
		assertThat(usefulness.groundedOutcome()).isEqualTo(1);
		assertThat(usefulness.humanVerificationChecks()).isEqualTo(1);
		assertThat(usefulness.total()).isEqualTo(2);
		assertThat(authored.followUpConsiderations()).hasSize(2);
	}

	@Test
	void serviceRejectsRefusalIncompleteMalformedAndUnsupportedResponsesWithoutDisplayingOrPersistingThem() throws Exception {
		MaritimeAiEvidencePackage input = ScenarioQualityStubClientTest.worsenedShipmentAInput("EnvelopeService");
		var assessments = mock(MaritimeAiAssessmentRepository.class);
		String refusal = """
				{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"refusal","refusal":"Not available"}]}]}
				""";
		String incomplete = """
				{"status":"incomplete","output":[{"type":"message","role":"assistant","status":"incomplete","content":[{"type":"output_text","text":"{\\"assessment\\":null}"}]}]}
				""";
		assertServiceResult(input, 200, refusal, assessments, "FAILED", "PROVIDER_REFUSAL");
		assertServiceResult(input, 200, incomplete, assessments, "FAILED", "INCOMPLETE_PROVIDER_OUTPUT");
		assertServiceResult(input, 503, "{\"error\":\"synthetic provider outage\"}", assessments,
				"ABSTAINED", "MODEL_UNAVAILABLE");
		assertServiceResult(input, 200, ScenarioQualityStubClient.fixtureResponsesEnvelope("not-json"), assessments,
				"FAILED", "INVALID_SCENARIO_OUTPUT");

		ObjectNode unsupported = (ObjectNode) json.readTree(ScenarioQualityStubClient.fixtureOutputText(input));
		((ObjectNode) unsupported.at("/assessment/deadline")).put("outcome", "NO_PREDICTED_IMPACT");
		assertServiceResult(input, 200,
				ScenarioQualityStubClient.fixtureResponsesEnvelope(json.writeValueAsString(unsupported)),
				assessments, "FAILED", "SCENARIO_FACT_MISMATCH");
		verifyNoInteractions(assessments);
	}

	private void assertServiceResult(MaritimeAiEvidencePackage input, int httpStatus, String envelope,
			MaritimeAiAssessmentRepository assessments, String expectedState, String expectedStatus) {
		var client = new ConfiguredMaritimeAiClient("openai-responses", "offline-service-test", "offline-only-key",
				"https://example.invalid/v1/responses", true, 20, 800, json,
				(request, body) -> new ConfiguredMaritimeAiClient.TransportResponse(httpStatus, envelope));
		var beans = new DefaultListableBeanFactory();
		var service = new MaritimeAiAssessmentService(null, null, null, client, assessments,
				new MaritimeAiAssessmentHistoryAccessPolicy(), json, beans.getBeanProvider(java.time.Clock.class),
				true, false, "", "");
		var response = service.generateScenarioTransient(input, true);
		assertThat(response.state()).isEqualTo(expectedState);
		assertThat(response.validationStatus()).isEqualTo(expectedStatus);
		assertThat(response.interpretation()).isNull();
		assertThat(response.assessmentId()).isNull();
		assertThat(response.limitation()).doesNotContain("Not available", "synthetic provider outage", "not-json");
	}

	@Test
	void deadlineGrammarExplainsExactShipmentMarginAndRejectsUnsupportedOrContradictoryClaims() throws Exception {
		MaritimeAiEvidencePackage input = ScenarioQualityStubClientTest.worsenedShipmentAInput("ExactImpactGrammar");
		String valid = ScenarioQualityStubClient.fixtureOutputText(input);
		String authored = json.readTree(valid).at("/assessment/deadline/statement").asString();
		assertThat(authored).contains("Shipment A", "20 hours");
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(authored, "A",
				input.shipmentContext().businessContext().comparison())).isTrue();
		assertThat(MaritimeAiResponseValidator.validateScenarioV16(valid, input).validationStatus()).isEqualTo("VALID");

		String withoutArticle = valid.replace("shows the predicted arrival exceeds", "shows predicted arrival exceeds");
		assertThat(MaritimeAiResponseValidator.validateScenarioV16(withoutArticle, input).valid()).isTrue();
		String withAlternativeMeaning = valid.replace("shows the predicted arrival exceeds the applicable transshipment-arrival deadline by 20 hours",
				"indicates the estimated arrival is 20 hours later than the applicable transshipment-arrival deadline");
		assertThat(MaritimeAiResponseValidator.validateScenarioV16(withAlternativeMeaning, input).valid()).isTrue();
		String conciseDirection = "The synthetic expected-arrival revision warrants review of the arrival estimate against the recorded milestone. "
				+ "The backend comparison shows estimated arrival exceeds the applicable transshipment-arrival deadline.";
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(conciseDirection, "A",
				input.shipmentContext().businessContext().comparison())).isTrue();
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(conciseDirection.replace("exceeds", "does not exceed"), "A",
				input.shipmentContext().businessContext().comparison())).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(conciseDirection.replace("exceeds", "is before"), "A",
				input.shipmentContext().businessContext().comparison())).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(conciseDirection + " The shipment is urgent.", "A",
				input.shipmentContext().businessContext().comparison())).isFalse();
		var worsenedB = ScenarioQualityStubClientTest.input(
				com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.create("ConciseB"), 2, 1);
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(conciseDirection, "B",
				worsenedB.shipmentContext().businessContext().comparison())).isFalse();

		List<String> unsupported = List.of(
			authored.replace("by 20 hours", "by 4 hours"),
			authored.replace("exceeds", "does not exceed"),
			authored.replace("exceeds the applicable", "is 20 hours before the applicable"),
			authored.replace("Shipment A", "Shipment B"),
			authored + " The shipment is urgent.",
			authored + " This may cause cargo damage and financial losses.",
			authored + " The predicted arrival is before the deadline.",
			authored + " Reroute the shipment immediately.");
		for (String statement : unsupported) {
			assertThat(ScenarioMaritimeAiProse.matchesDeadline(statement, "A",
					input.shipmentContext().businessContext().comparison())).isFalse();
			assertPath(valid, input, "/assessment/deadline/statement", statement, "SCENARIO_PROSE_MISMATCH");
		}

		assertPath(valid, input, "/assessment/deadline/outcome", "NO_PREDICTED_IMPACT", "SCENARIO_FACT_MISMATCH");
		assertPath(valid, input, "/assessment/deadline/evidenceRef", "evidence:cdg279-v1-notice-2", "SCENARIO_EVIDENCE_MISMATCH");
		assertPath(valid, input, "/assessment/deadline/evidenceRef", "", "INVALID_SCENARIO_OUTPUT");
	}

	@Test
	void everyPromptedAlternativeIsAcceptedForItsTypedMeaning() {
		for (var state : List.of(MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE,
				MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW)) {
			for (String statement : ScenarioMaritimeAiProse.riskAlternatives(ShipmentRisk.Level.HIGH, state))
				assertThat(ScenarioMaritimeAiProse.matchesRisk(statement, ShipmentRisk.Level.HIGH, state)).isTrue();
		}
		var worsenedA = ScenarioQualityStubClientTest.worsenedShipmentAInput("PromptGrammarA");
		var worsenedB = ScenarioQualityStubClientTest.input(
				com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.create("PromptGrammarB"), 2, 1);
		for (var input : List.of(worsenedA, worsenedB)) {
			var comparison = input.shipmentContext().businessContext().comparison();
			String key = input.shipmentContext().scenario().shipmentKey();
			String prompt = ScenarioMaritimeAiProse.promptInstructions() + " "
					+ ScenarioMaritimeAiProse.deadlinePromptInstructions(key, comparison);
			assertThat(ScenarioMaritimeAiProse.promptChoicePhrases(ScenarioDeadlineImpact.from(comparison))).isNotEmpty()
					.allSatisfy(choice -> assertThat(prompt).contains(choice));
			for (String statement : ScenarioMaritimeAiProse.deadlineAlternatives(key, comparison))
				assertThat(ScenarioMaritimeAiProse.matchesDeadline(statement, key, comparison)).isTrue();
			assertThat(ScenarioMaritimeAiProse.deadlinePromptInstructions(key, comparison))
				.contains("Shipment " + key, ScenarioDeadlineImpact.from(comparison).magnitude());
		}
		ScenarioMaritimeAiProse.uncertaintyAlternatives().forEach(text -> assertThat(ScenarioMaritimeAiProse.matchesUncertainty(text)).isTrue());
		ScenarioMaritimeAiProse.etaCheckAlternatives().forEach(text -> assertThat(ScenarioMaritimeAiProse.matchesEtaCheck(text)).isTrue());
		ScenarioMaritimeAiProse.deadlineCheckAlternatives().forEach(text -> assertThat(ScenarioMaritimeAiProse.matchesDeadlineCheck(text)).isTrue());
	}

	@Test
	void controlledPromptGrammarAcceptsLegitimateParaphrasesAndRejectsUnsupportedAdditions() {
		var worsenedA = ScenarioQualityStubClientTest.worsenedShipmentAInput("ParaphraseA");
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(
				"The synthetic arrival revision gives the operations team a reason to check the arrival estimate against the recorded milestone. "
						+ "The backend comparison for Shipment A indicates the estimated arrival is 20 hours beyond the applicable transshipment-arrival deadline.",
				"A", worsenedA.shipmentContext().businessContext().comparison())).isTrue();
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
		var exactDeadline = ScenarioMaritimeAiProse.deadlineAlternatives("A",
				worsenedA.shipmentContext().businessContext().comparison()).getFirst();
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(exactDeadline
				+ " This will cause a missed connection and financial loss.", "A",
				worsenedA.shipmentContext().businessContext().comparison())).isFalse();
		var worsenedB = ScenarioQualityStubClientTest.input(
				com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.create("WrongScenarioOutcome"), 2, 1);
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(exactDeadline, "B",
				worsenedB.shipmentContext().businessContext().comparison())).isFalse();
		assertThat(ScenarioMaritimeAiProse.matchesDeadline(exactDeadline + " Reroute the shipment immediately.", "A",
				worsenedA.shipmentContext().businessContext().comparison())).isFalse();
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
		var worsenedB = ScenarioQualityStubClientTest.input(
				com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.create("WrongProseOutcome"), 2, 1);
		assertPath(valid, input, "/assessment/deadline/statement", ScenarioMaritimeAiProse.deadlineAlternatives("B",
				worsenedB.shipmentContext().businessContext().comparison()).getFirst(), "SCENARIO_PROSE_MISMATCH");
		assertPath(valid, input, "/assessment/uncertainty", "The synthetic maritime evidence establishes the vessel arrived.", "SCENARIO_PROSE_MISMATCH");
		assertPath(valid, input, "/assessment/etaCheck", "Reroute the shipment now.", "SCENARIO_PROSE_MISMATCH");
		assertPath(valid, input, "/assessment/deadlineCheck", "Ask operations to change the deadline.", "SCENARIO_PROSE_MISMATCH");
		assertThat(MaritimeAiResponseValidator.validateScenarioV16("{\"assessment\":null}", input).validationStatus())
				.isEqualTo("INSUFFICIENT_MODEL_EVIDENCE");
		assertThat(MaritimeAiResponseValidator.validateScenarioV16(valid, withoutEligibleEvidence(input)).validationStatus())
				.isEqualTo("INSUFFICIENT_SCENARIO_CONTEXT");
	}

	@Test
	void preflightRejectsBackendComparisonOutcomeThatConflictsWithItsEtaAndDeadline() {
		MaritimeAiEvidencePackage base = ScenarioQualityStubClientTest.worsenedShipmentAInput("ComparisonConsistency");
		var context = base.shipmentContext();
		var originalBusiness = context.businessContext();
		var originalComparison = originalBusiness.comparison();
		var inconsistent = new MaritimeAiBusinessContext.Comparison(
				MaritimeAiBusinessContext.ComparisonState.AVAILABLE, DelayComparison.Outcome.NO_PREDICTED_IMPACT, null,
				originalComparison.estimatedArrivalAt(), originalComparison.comparedDeadline(),
				originalComparison.recordedTransshipmentPort());
		var updatedContext = new MaritimeAiShipmentContext(context.transshipmentPort(), context.motherVessel(), context.feederVessel(),
				context.connectionRisk(), new MaritimeAiBusinessContext(originalBusiness.deadline(), inconsistent), context.scenario());
		var input = new MaritimeAiEvidencePackage(base.bundleVersion(), base.assessmentCutoffUtc(),
				base.shipmentAssociationConfidence(), updatedContext, base.evidence());

		assertThat(ScenarioMaritimeAiOutput.preflightFailureField(input)).isEqualTo("shipmentContext.businessContext");
		var validation = MaritimeAiResponseValidator.validateScenarioV16(ScenarioQualityStubClient.fixtureOutputText(base), input);
		assertThat(validation.validationStatus()).isEqualTo("INSUFFICIENT_SCENARIO_CONTEXT");
		assertThat(validation.interpretation()).isNull();
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
	void sharedAuthoredTextRubricProducesZeroOneAndTwoPointCases() throws Exception {
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
		var noImpactInput = ScenarioQualityStubClientTest.input(
				com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.create("ContradictoryRubric"), 2, 1);
		String contradictoryOutcome = ScenarioMaritimeAiProse.deadlineAlternatives("B",
				noImpactInput.shipmentContext().businessContext().comparison()).getFirst();
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

	private String genuineWorsenedAResponse() throws IOException {
		try (var resource = getClass().getResourceAsStream(
				"/com/drift/backend/maritimeai/genuine-worsened-a-v16.json")) {
			assertThat(resource).isNotNull();
			return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
		}
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
		return ScenarioAiUsefulnessRubric.evaluate(authored, input).total();
	}
}
