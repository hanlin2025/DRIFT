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
	@Test
	void fourPositiveScenarioInterpretationsPassGroundingAndScoreTwoOutOfTwo() {
		var fixture = SyntheticDisruptionScenarioFixture.create("Quality");
		var client = new ScenarioQualityStubClient();

		for (int stageIndex = 1; stageIndex < 3; stageIndex++) {
			for (int shipmentIndex = 0; shipmentIndex < 2; shipmentIndex++) {
				MaritimeAiEvidencePackage input = input(fixture, stageIndex, shipmentIndex);
				MaritimeAiClient.MaritimeAiClientResult result = client.interpret(input);
				assertThat(MaritimeAiResponseValidator.validate(result.interpretation(), input))
						.as("%s shipment %s grounding", input.shipmentContext().scenario().stage(),
							input.shipmentContext().scenario().shipmentKey()).isNull();
				assertThat(result.promptVersion()).isEqualTo(ConfiguredMaritimeAiClient.SCENARIO_PROMPT_VERSION);
				assertThat(input.shipmentContext().connectionRisk().riskLevel().name())
						.isEqualTo(stageIndex == 1 ? "MEDIUM" : "HIGH");
				assertThat(input.shipmentContext().businessContext().comparison().outcome().name())
						.isEqualTo(shipmentIndex == 0 ? "EXCEEDS_DEADLINE" : "NO_PREDICTED_IMPACT");
				assertUsefulness(result.interpretation(), input);
			}
		}
		assertThat(client.scenarioInputs()).hasSize(4);
	}

	private static MaritimeAiEvidencePackage input(SyntheticDisruptionScenarioFixture.Scenario fixture,
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
		return new MaritimeAiEvidencePackage(fixture.contractVersion(), cutoff.toString(), "VERIFIED", shipmentContext, evidence);
	}

	private static void assertUsefulness(MaritimeAiInterpretation interpretation, MaritimeAiEvidencePackage input) {
		String expected = input.shipmentContext().businessContext().comparison().outcome().name();
		MaritimeAiClaim business = interpretation.claims().stream()
				.filter(claim -> "DELAY_COMPARISON_REVIEW".equals(claim.relationshipType())).findFirst().orElseThrow();
		String statement = business.statement().toLowerCase();
		assertThat(business.factReferences()).contains("comparison:DELAY_OUTCOME",
				"business:TRANSSHIPMENT_ARRIVAL_DEADLINE_APPLICABILITY");
		if ("EXCEEDS_DEADLINE".equals(expected)) assertThat(statement).contains("predicted arrival exceeds", "applicable");
		else assertThat(statement).contains("no predicted impact", "applicable");

		List<String> followUps = interpretation.followUpConsiderations().stream().map(String::toLowerCase).toList();
		assertThat(followUps).anySatisfy(text -> assertThat(text)
				.contains("carrier operations team", "verify", "arrival estimate"));
		assertThat(followUps).anySatisfy(text -> assertThat(text)
				.contains("operations team", "confirm", "transshipment-arrival deadline", "current shipment port"));
		int score = (statement.contains("predicted arrival exceeds") || statement.contains("no predicted impact") ? 1 : 0)
				+ (followUps.stream().anyMatch(text -> text.contains("carrier operations team")
						&& text.contains("verify") && text.contains("arrival estimate"))
					&& followUps.stream().anyMatch(text -> text.contains("operations team") && text.contains("confirm")
							&& text.contains("transshipment-arrival deadline") && text.contains("current shipment port")) ? 1 : 0);
		assertThat(score).as("shipment %s usefulness", input.shipmentContext().scenario().shipmentKey()).isEqualTo(2);
	}
}
