package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture;
import com.drift.backend.shipment.DelayComparison;
import com.drift.backend.shipment.ShipmentRisk;

import tools.jackson.databind.ObjectMapper;

class MaritimeAiScenarioTransientTest {
	@Test
	void configuredProviderResultMustPassTheExistingValidatorAndIsNotPersisted() throws Exception {
		ObjectMapper json = new ObjectMapper();
		MaritimeAiInterpretation output = validOutput();
		ConfiguredMaritimeAiClient provider = configured(json, output);
		var assessments = mock(MaritimeAiAssessmentRepository.class);
		MaritimeAiAssessmentService service = service(json, provider, assessments);

		MaritimeAiAssessmentResponse response = service.generateScenarioTransient(input(syntheticEvidence()), true);

		assertThat(response.assessmentId()).isNull();
		assertThat(response.state()).isEqualTo("GENERATED");
		assertThat(response.validationStatus()).isEqualTo("VALID");
		assertThat(response.interpretation()).isEqualTo(output);
		assertThat(response.promptVersion()).isEqualTo(ConfiguredMaritimeAiClient.SCENARIO_PROMPT_VERSION);
		verifyNoInteractions(assessments);
	}

	@Test
	void validatorFailureCodeIsReturnedAndAuthoredClaimsAreNotExposed() throws Exception {
		ObjectMapper json = new ObjectMapper();
		MaritimeAiInterpretation unsupported = new MaritimeAiInterpretation("Review the grounded interpretation and limitations.",
				List.of(new MaritimeAiClaim("The cargo is delayed.", List.of("weather-1"), "REGIONAL_CONTEXT", null, null,
						"REGIONAL_CONTEXT", List.of("evidence:weather-1"), "REGIONAL_WEATHER_CONTEXT")),
				List.of(), List.of(), true, null);
		var service = service(json, configured(json, unsupported), mock(MaritimeAiAssessmentRepository.class));

		MaritimeAiAssessmentResponse response = service.generateScenarioTransient(input(syntheticEvidence()), true);

		assertThat(response.state()).isEqualTo("FAILED");
		assertThat(response.validationStatus()).isEqualTo("UNSUPPORTED_CARGO_ASSERTION");
		assertThat(response.interpretation()).isNull();
		assertThat(response.limitation()).contains("UNSUPPORTED_CARGO_ASSERTION");
	}

	@Test
	void replayOrMockClientsAndAuthenticEvidenceCannotReachTheScenarioProviderPath() throws Exception {
		AtomicBoolean called = new AtomicBoolean();
		MaritimeAiClient replay = ignored -> {
			called.set(true);
			return new MaritimeAiClient.MaritimeAiClientResult("controlled-replay-stub", "offline", "stub", validOutput(), null);
		};
		var service = service(new ObjectMapper(), replay, mock(MaritimeAiAssessmentRepository.class));
		MaritimeAiAssessmentResponse response = service.generateScenarioTransient(input(syntheticEvidence()), true);
		assertThat(response.state()).isEqualTo("ABSTAINED");
		assertThat(response.validationStatus()).isEqualTo("REAL_PROVIDER_REQUIRED");
		assertThat(called).isFalse();

		MaritimeEvidence authentic = new MaritimeEvidence("MPA_ETA_REVISION", "LIVE", "live-1", "Live revision", "MPA", "", "citation",
				null, null, Instant.now().toString(), "FRESH", null, null, null, "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN",
				true, true, "VERIFIED", "Live evidence is not permitted.");
		AtomicBoolean configuredTransportCalled = new AtomicBoolean();
		ObjectMapper json = new ObjectMapper();
		var configuredClient = new ConfiguredMaritimeAiClient("test-json-provider", "test-model", "unused-key",
				"https://unused.invalid", true, 20, 800, json, (request, body) -> {
					configuredTransportCalled.set(true);
					return new ConfiguredMaritimeAiClient.TransportResponse(200, json.writeValueAsString(validOutput()));
				});
		MaritimeAiAssessmentResponse blocked = service(json, configuredClient, mock(MaritimeAiAssessmentRepository.class))
				.generateScenarioTransient(input(authentic), true);
		assertThat(blocked.state()).isEqualTo("ABSTAINED");
		assertThat(blocked.validationStatus()).isEqualTo("SYNTHETIC_EVIDENCE_REQUIRED");
		assertThat(configuredTransportCalled).isFalse();
	}

	@Test
	void explicitlyEnabledOfflineScenarioStubIsBoundedAndProducesValidatedLabeledResultsForBothOutcomes() {
		var fixture = SyntheticDisruptionScenarioFixture.create("OFFLINE");
		var change = fixture.stages().get(1);
		var worsened = fixture.stages().get(2);
		AtomicInteger calls = new AtomicInteger();
		ControlledScenarioReplayAiClient stub = new ControlledScenarioReplayAiClient() {
			@Override public MaritimeAiClientResult interpret(MaritimeAiEvidencePackage input) {
				calls.incrementAndGet();
				return super.interpret(input);
			}
		};
		var service = service(new ObjectMapper(), stub, mock(MaritimeAiAssessmentRepository.class));

		MaritimeAiAssessmentResponse baseline = service.generateScenarioTransient(
				scenarioInput(fixture, "A", "BASELINE", List.of(), ShipmentRisk.Level.LOW,
						DelayComparison.Outcome.NO_PREDICTED_IMPACT), true);
		assertThat(baseline.state()).isEqualTo("ABSTAINED");
		assertThat(baseline.validationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
		assertThat(calls).hasValue(0);

		var changeEvidence = MaritimeAiAssessmentService.eligibleForInference(change.evidence());
		var worsenedEvidence = MaritimeAiAssessmentService.eligibleForInference(worsened.evidence());
		for (var sample : List.of(
				scenarioInput(fixture, "A", "CHANGE", changeEvidence, ShipmentRisk.Level.MEDIUM, DelayComparison.Outcome.EXCEEDS_DEADLINE),
				scenarioInput(fixture, "B", "CHANGE", changeEvidence, ShipmentRisk.Level.MEDIUM, DelayComparison.Outcome.NO_PREDICTED_IMPACT),
				scenarioInput(fixture, "A", "WORSENED", worsenedEvidence, ShipmentRisk.Level.HIGH, DelayComparison.Outcome.EXCEEDS_DEADLINE),
				scenarioInput(fixture, "B", "WORSENED", worsenedEvidence, ShipmentRisk.Level.HIGH, DelayComparison.Outcome.NO_PREDICTED_IMPACT))) {
			MaritimeAiAssessmentResponse response = service.generateScenarioTransient(sample, true);
			assertThat(response.assessmentId()).isNull();
			assertThat(response.state()).withFailMessage("Offline scenario stub validation failed for %s/%s: %s",
					sample.shipmentContext().scenario().stage(), sample.shipmentContext().scenario().shipmentKey(),
					response.validationStatus()).isEqualTo("GENERATED");
			assertThat(response.validationStatus()).isEqualTo("VALID");
			assertThat(response.provider()).isEqualTo("controlled-replay-stub");
			assertThat(response.model()).isEqualTo("offline");
			assertThat(response.promptVersion()).isEqualTo("cdg290.offline-scenario-stub.v1");
			assertThat(response.interpretation()).isNotNull();
			assertThat(MaritimeAiResponseValidator.validate(response.interpretation(), sample)).isNull();
			if (sample.shipmentContext().scenario().stage().equals("WORSENED")) {
				assertThat(response.interpretation().claims()).extracting(MaritimeAiClaim::relationshipType)
						.containsOnly("PORT_CONTEXT_REVIEW", "REGIONAL_WEATHER_CONTEXT");
				assertThat(response.interpretation().claims()).extracting(MaritimeAiClaim::statement)
						.noneMatch(statement -> statement.toLowerCase().contains("shipment") || statement.toLowerCase().contains("cargo"));
			}
		}
		assertThat(calls).hasValue(4);
	}

	@Test
	void scenarioFeatureGateAndInsufficientEvidenceAbstainBeforeProviderUse() {
		AtomicBoolean called = new AtomicBoolean();
		MaritimeAiClient provider = ignored -> {
			called.set(true);
			return MaritimeAiClient.MaritimeAiClientResult.unavailable("unexpected");
		};
		var service = service(new ObjectMapper(), provider, mock(MaritimeAiAssessmentRepository.class));
		assertThat(service.generateScenarioTransient(input(syntheticEvidence()), false).validationStatus()).isEqualTo("SCENARIO_AI_DISABLED");
		assertThat(called).isFalse();
	}

	private static ConfiguredMaritimeAiClient configured(ObjectMapper json, MaritimeAiInterpretation output) throws Exception {
		String body = json.writeValueAsString(output);
		return new ConfiguredMaritimeAiClient("test-json-provider", "test-model", "never-printed", "https://unused.invalid",
				true, 20, 800, json, (request, requestBody) -> new ConfiguredMaritimeAiClient.TransportResponse(200, body));
	}

	private static MaritimeAiAssessmentService service(ObjectMapper json, MaritimeAiClient client,
			MaritimeAiAssessmentRepository assessments) {
		var beans = new DefaultListableBeanFactory();
		return new MaritimeAiAssessmentService(null, null, null, client, assessments,
				new MaritimeAiAssessmentHistoryAccessPolicy(), json, beans.getBeanProvider(java.time.Clock.class), true,
				false, "", "");
	}

	private static MaritimeAiInterpretation validOutput() {
		return new MaritimeAiInterpretation("Review the grounded interpretation and limitations.",
				List.of(new MaritimeAiClaim("The fictional regional weather context does not establish an operational impact.",
						List.of("weather-1"), "REGIONAL_CONTEXT", null, null, "REGIONAL_CONTEXT",
						List.of("evidence:weather-1"), "REGIONAL_WEATHER_CONTEXT")),
				List.of("The synthetic context does not establish a vessel-specific cause."),
				List.of(), true, null);
	}

	private static MaritimeAiEvidencePackage input(MaritimeEvidence evidence) {
		var scenario = new MaritimeAiShipmentContext.Scenario("A", "SYNTH-A", "CHANGE", "2026-10-01T04:00:00Z");
		var context = new MaritimeAiShipmentContext("Synthetic port", null, null, null, null, scenario);
		return new MaritimeAiEvidencePackage("cdg246.maritime-evidence-bundle.v1", "2026-10-01T04:00:00Z", "VERIFIED",
				context, List.of(evidence));
	}

	private static MaritimeAiEvidencePackage scenarioInput(SyntheticDisruptionScenarioFixture.Scenario fixture,
			String key, String stage, List<MaritimeEvidence> evidence, ShipmentRisk.Level risk,
			DelayComparison.Outcome outcome) {
		Instant cutoff = Instant.parse(stage.equals("BASELINE") ? "2026-10-01T00:00:00Z"
				: stage.equals("CHANGE") ? "2026-10-01T04:00:00Z" : "2026-10-01T08:00:00Z");
		var riskContext = new MaritimeAiConnectionRiskContext(true, null, risk,
				MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE,
				new MaritimeAiConnectionRiskContext.PlannedWindow(MaritimeAiConnectionRiskContext.WindowState.AVAILABLE, 108_000L),
				new MaritimeAiConnectionRiskContext.RemainingWindow("AVAILABLE", 14_400L, null),
				new MaritimeAiConnectionRiskContext.ArrivalEstimateFact(MaritimeAiConnectionRiskContext.EstimateState.AVAILABLE,
						OffsetDateTime.parse(stage.equals("CHANGE") ? "2026-10-01T16:00:00Z" : "2026-10-02T08:00:00Z"), cutoff, null), List.of());
		OffsetDateTime deadline = OffsetDateTime.parse(key.equals("A") ? "2026-10-01T12:00:00Z" : "2026-10-02T10:00:00Z");
		OffsetDateTime estimate = OffsetDateTime.parse(stage.equals("CHANGE") ? "2026-10-01T16:00:00Z" : "2026-10-02T08:00:00Z");
		var business = new MaritimeAiBusinessContext(
				new MaritimeAiBusinessContext.Deadline(MaritimeAiBusinessContext.DeadlineState.RECORDED, deadline,
						fixture.transshipmentPort(), true),
				new MaritimeAiBusinessContext.Comparison(MaritimeAiBusinessContext.ComparisonState.AVAILABLE, outcome, null,
						estimate, deadline, fixture.transshipmentPort()));
		var scenario = new MaritimeAiShipmentContext.Scenario(key, "SYNTH-" + key, stage, cutoff.toString());
		var context = new MaritimeAiShipmentContext(fixture.transshipmentPort(),
				new MaritimeAiShipmentContext.Vessel("MOTHER", fixture.motherVessel(), null, "PLANNED_ARRIVAL", fixture.plannedMotherArrivalAt()),
				new MaritimeAiShipmentContext.Vessel("FEEDER", fixture.feederVessel(), null, "PLANNED_DEPARTURE", fixture.plannedFeederDepartureAt()),
				riskContext, business, scenario);
		return new MaritimeAiEvidencePackage(fixture.contractVersion(), cutoff.toString(), "VERIFIED", context, evidence);
	}

	private static MaritimeEvidence syntheticEvidence() {
		return new MaritimeEvidence("NEA_WEATHER", "SYNTHETIC_DEMONSTRATION", "weather-1", "Fictional weather context", "fixture", "", "Fixture citation",
				"2026-10-01T03:00:00Z", "2026-10-01T03:00:00Z", "2026-10-01T04:00:00Z", "FRESH", null,
				"Fictional regional weather.", null, "UNKNOWN", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true,
				"VERIFIED", "Synthetic regional context only.", null, null, null, "REGIONAL_CONTEXT");
	}
}
