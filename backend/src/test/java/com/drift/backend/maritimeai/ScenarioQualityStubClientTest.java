package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture;
import com.drift.backend.shipment.ArrivalEstimate;
import com.drift.backend.shipment.ConnectionWindow;
import com.drift.backend.shipment.ConnectionWindowCalculation;
import com.drift.backend.shipment.ControlledScenarioSnapshot;
import com.drift.backend.shipment.DelayComparison;
import com.drift.backend.shipment.RemainingConnectionWindow;
import com.drift.backend.shipment.ShipmentRisk;

class ScenarioQualityStubClientTest {
	static MaritimeAiEvidencePackage worsenedShipmentAInput(String runId) {
		return input(SyntheticDisruptionScenarioFixture.create(runId), 2, 0);
	}

	@Test
	void fourPositiveScenarioInterpretationsPassGroundingAndScoreTwoOutOfTwo() {
		var fixture = SyntheticDisruptionScenarioFixture.create("Quality");
		var client = new ScenarioQualityStubClient();

		for (int stageIndex = 1; stageIndex < 3; stageIndex++) {
			for (int shipmentIndex = 0; shipmentIndex < 2; shipmentIndex++) {
				MaritimeAiEvidencePackage input = input(fixture, stageIndex, shipmentIndex);
				MaritimeAiClient.MaritimeAiClientResult result = client.interpret(input);
				assertThat(result.interpretation()).isNull();
				assertThat(result.scenarioOutputText()).isNotBlank();
				var validation = MaritimeAiResponseValidator.validateScenarioV16(result.scenarioOutputText(), input);
				assertThat(validation.valid())
						.as("%s shipment %s grounding", input.shipmentContext().scenario().stage(),
							input.shipmentContext().scenario().shipmentKey()).isTrue();
				assertThat(result.promptVersion()).isEqualTo(ConfiguredMaritimeAiClient.OPENAI_SCENARIO_PROMPT_VERSION);
				assertThat(input.shipmentContext().connectionRisk().riskLevel().name())
						.isEqualTo(stageIndex == 1 ? "MEDIUM" : "HIGH");
				assertThat(input.shipmentContext().businessContext().comparison().outcome().name())
						.isEqualTo(shipmentIndex == 0 ? "EXCEEDS_DEADLINE" : "NO_PREDICTED_IMPACT");
				assertUsefulness(validation.interpretation(), input);
			}
		}
		assertThat(client.scenarioInputs()).hasSize(4);
	}

	@Test
	void worsenedShipmentAReproducesBusinessConstraintRejectionReasonsWithoutLeakingClaimText() {
		var fixture = SyntheticDisruptionScenarioFixture.create("BusinessDiagnostic");
		MaritimeAiEvidencePackage input = input(fixture, 2, 0);
		assertThat(input.shipmentContext().scenario().stage()).isEqualTo("WORSENED");
		assertThat(input.shipmentContext().scenario().shipmentKey()).isEqualTo("A");
		assertThat(input.shipmentContext().businessContext().comparison().outcome().name()).isEqualTo("EXCEEDS_DEADLINE");

		var response = new ScenarioQualityStubClient().interpret(input);
		var riskClaim = MaritimeAiResponseValidator.validateScenarioV16(response.scenarioOutputText(), input)
				.interpretation().claims().getFirst();
		String evidenceId = riskClaim.evidenceIds().getFirst();
		var revision = input.evidence().stream().filter(item -> item.evidenceId().equals(evidenceId)).findFirst().orElseThrow();
		List<String> canonicalReferences = new java.util.ArrayList<>(MaritimeAiCanonicalFactReferences.businessReferences(input));
		canonicalReferences.add("evidence:" + evidenceId);
		String groundedStatement = "The synthetic expected-arrival revision warrants review; the backend comparison shows "
				+ "the predicted arrival exceeds the applicable transshipment-arrival deadline.";
		var businessClaim = new MaritimeAiClaim(groundedStatement, List.of(evidenceId), "CONTEXTUAL",
				revision.vesselRole(), revision.observationType(), revision.associationScope(), canonicalReferences,
				"DELAY_COMPARISON_REVIEW");
		assertThat(MaritimeAiResponseValidator.validate(new MaritimeAiInterpretation("Review the grounded interpretation.",
				List.of(businessClaim), List.of(), List.of(
						"Ask the carrier operations team to verify whether the latest arrival estimate remains current at the scenario cutoff.",
						"Ask the operations team to confirm whether the transshipment-arrival deadline remains applicable to the current shipment port."), true, null), input)).isNull();

		List<String> missingReferences = canonicalReferences.stream()
				.filter(reference -> !MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_APPLICABILITY.equals(reference)).toList();
		List<DiagnosticCase> cases = List.of(
				new DiagnosticCase(groundedStatement, missingReferences, "CANONICAL_BUSINESS_REFERENCE_MISSING"),
				new DiagnosticCase(groundedStatement + " The shipment is urgent.", canonicalReferences,
						"URGENCY_ASSERTION_NOT_AUTHORIZED"),
				new DiagnosticCase("The synthetic expected-arrival revision warrants review; the backend comparison predicts arrival exceeds "
						+ "the scenario-defined transshipment-arrival deadline.", canonicalReferences,
						"APPLICABILITY_WORDING_MISSING"),
				new DiagnosticCase("The synthetic expected-arrival revision warrants review; the predicted arrival exceeds the scenario-defined "
						+ "deadline, which is applicable to the current transshipment port.", canonicalReferences,
						"TRANSSHIPMENT_ARRIVAL_DEADLINE_TERM_MISSING"),
				new DiagnosticCase("The synthetic expected-arrival revision warrants review against the scenario-defined transshipment-arrival "
						+ "deadline and it is applicable to the current transshipment port.", canonicalReferences,
						"CLAUSE_SPLIT_SEPARATED_DEADLINE_AND_APPLICABILITY"));

		for (DiagnosticCase diagnosticCase : cases) {
			var rejectedBusinessClaim = new MaritimeAiClaim(diagnosticCase.statement(), List.of(evidenceId), "CONTEXTUAL",
					revision.vesselRole(), revision.observationType(), revision.associationScope(), diagnosticCase.references(),
					"DELAY_COMPARISON_REVIEW");
			List<MaritimeAiClaim> claims = List.of(riskClaim, riskClaim, riskClaim, riskClaim, rejectedBusinessClaim);
			var output = new MaritimeAiInterpretation("Review the grounded interpretation.", claims,
					List.of(), List.of(), true, null);

			assertThat(MaritimeAiResponseValidator.validate(output, input))
					.as("WORSENED A validator reproduction: %s", diagnosticCase.reason()).isEqualTo("UNSUPPORTED_BUSINESS_CONSTRAINT");
			assertThat(MaritimeAiResponseValidator.safeValidationDiagnostic("UNSUPPORTED_BUSINESS_CONSTRAINT", output, input))
					.as("WORSENED A safe diagnostic: %s", diagnosticCase.reason())
					.contains("at claims[4].statement (" + diagnosticCase.reason())
					.doesNotContain(diagnosticCase.statement());
		}
	}

	static MaritimeAiEvidencePackage input(SyntheticDisruptionScenarioFixture.Scenario fixture,
			int stageIndex, int shipmentIndex) {
		var stage = fixture.stages().get(stageIndex);
		var shipment = fixture.shipments().get(shipmentIndex);
		String key = shipmentIndex == 0 ? "A" : "B";
		String eta = stageIndex == 1 ? "2026-10-01T16:00:00Z" : "2026-10-02T08:00:00Z";
		OffsetDateTime estimatedArrival = OffsetDateTime.parse(eta);
		OffsetDateTime deadline = shipment.deadline();
		String outcome = estimatedArrival.toInstant().isAfter(deadline.toInstant())
				? "EXCEEDS_DEADLINE" : "NO_PREDICTED_IMPACT";
		long remainingSeconds = stageIndex == 1 ? 72_000L : 14_400L;
		long estimateDifferenceSeconds = stageIndex == 1 ? 36_000L : 93_600L;
		ShipmentRisk.Level severity = stageIndex == 1 ? ShipmentRisk.Level.MEDIUM : ShipmentRisk.Level.HIGH;
		Instant cutoff = stage.cutoff();
		var remaining = new RemainingConnectionWindow(RemainingConnectionWindow.Status.AVAILABLE,
				stageIndex == 1 ? "20 hours" : "4 hours", remainingSeconds, null, null);
		var estimate = new ArrivalEstimate(estimatedArrival, ArrivalEstimate.LIMITATION, BigDecimal.valueOf(3),
				ArrivalEstimate.Classification.BEHIND_PLAN, "Behind plan", estimateDifferenceSeconds, 0,
				cutoff, false, null);
		var comparison = new DelayComparison(DelayComparison.Outcome.valueOf(outcome), null,
				"Fixture deterministic comparison", estimatedArrival, deadline, fixture.transshipmentPort());
		var row = new ControlledScenarioSnapshot.ShipmentRead(key, (long) shipmentIndex + 1,
				shipment.shipment().shipmentReference(), fixture.transshipmentPort(),
				shipment.shipment().plannedMotherArrivalAt(),
				shipment.shipment().plannedFeederDepartureAt(),
				new ConnectionWindowCalculation(ConnectionWindowCalculation.Status.AVAILABLE,
						new ConnectionWindow("30 hours", 108_000L)), remaining, estimate,
				new ShipmentRisk(severity, "Backend-calculated scenario risk."), comparison);
		var scenarioContext = new MaritimeAiShipmentContext.Scenario(key, shipment.shipment().shipmentReference(),
				stageIndex == 1 ? "CHANGE" : "WORSENED", cutoff.toString());
		var shipmentContext = new MaritimeAiShipmentContext(fixture.transshipmentPort(),
				new MaritimeAiShipmentContext.Vessel("MOTHER", fixture.motherVessel(), null, "PLANNED_ARRIVAL",
						shipment.shipment().plannedMotherArrivalAt()),
				new MaritimeAiShipmentContext.Vessel("FEEDER", fixture.feederVessel(), null, "PLANNED_DEPARTURE",
						shipment.shipment().plannedFeederDepartureAt()),
				MaritimeAiConnectionRiskContext.fromScenario(row),
				MaritimeAiBusinessContext.fromScenario(comparison, fixture.transshipmentPort()), scenarioContext);
		List<MaritimeEvidence> evidence = MaritimeAiAssessmentService.eligibleForInference(
				fixture.eligibleEvidenceFor(shipment, cutoff));
		return new MaritimeAiEvidencePackage("cdg246.maritime-evidence-bundle.v1", cutoff.toString(), "VERIFIED", shipmentContext, evidence);
	}

	static void assertUsefulness(MaritimeAiInterpretation interpretation, MaritimeAiEvidencePackage input) {
		String expected = input.shipmentContext().businessContext().comparison().outcome().name();
		var comparison = input.shipmentContext().businessContext().comparison();
		assertThat(comparison.state().name()).isEqualTo("AVAILABLE");
		List<MaritimeAiClaim> businessClaims = interpretation.claims().stream()
				.filter(claim -> "DELAY_COMPARISON_REVIEW".equals(claim.relationshipType())).toList();
		assertThat(businessClaims).hasSize(1);
		MaritimeAiClaim businessClaim = businessClaims.getFirst();
		String businessStatement = businessClaim.statement().toLowerCase();
		if ("EXCEEDS_DEADLINE".equals(expected)) assertThat(businessStatement).contains("predicted arrival exceeds");
		else assertThat(businessStatement).contains("no predicted impact");
		assertThat(businessStatement).contains("synthetic", "expected-arrival revision", "transshipment-arrival deadline", "applicable");
		assertThat(businessClaim.factReferences()).contains("evidence:" + businessClaim.evidenceIds().getFirst(),
				MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE, MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_PORT,
				MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_APPLICABILITY, MaritimeAiCanonicalFactReferences.COMPARISON_OUTCOME,
				MaritimeAiCanonicalFactReferences.COMPARISON_ESTIMATED_ARRIVAL,
				MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_DEADLINE,
				MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_PORT);
		MaritimeAiClaim risk = interpretation.claims().stream()
				.filter(claim -> "RISK_BUFFER_REVIEW".equals(claim.relationshipType())).findFirst().orElseThrow();
		String statement = risk.statement().toLowerCase();
		assertThat(statement).contains("synthetic", "revision", "warrants human review", input.shipmentContext().connectionRisk().riskLevel().name().toLowerCase());
		assertThat(risk.factReferences()).contains("evidence:" + risk.evidenceIds().getFirst(), "risk:CONNECTION_SEVERITY");

		List<String> followUps = interpretation.followUpConsiderations().stream().map(String::toLowerCase).toList();
		assertThat(followUps).anySatisfy(text -> assertThat(text)
				.contains("carrier operations team", "verify", "arrival estimate"));
		assertThat(followUps).anySatisfy(text -> assertThat(text)
				.contains("operations team", "confirm", "transshipment-arrival deadline", "current shipment port"));
		int score = (comparison.outcome().name().equals(expected)
				&& ("EXCEEDS_DEADLINE".equals(expected) ? businessStatement.contains("predicted arrival exceeds")
						: businessStatement.contains("no predicted impact")) ? 1 : 0)
				+ (followUps.stream().anyMatch(text -> text.contains("carrier operations team")
						&& text.contains("verify") && text.contains("arrival estimate"))
					&& followUps.stream().anyMatch(text -> text.contains("operations team") && text.contains("confirm")
							&& text.contains("transshipment-arrival deadline") && text.contains("current shipment port")) ? 1 : 0);
		assertThat(score).as("shipment %s usefulness", input.shipmentContext().scenario().shipmentKey()).isEqualTo(2);
	}

	private record DiagnosticCase(String statement, List<String> references, String reason) { }
}
