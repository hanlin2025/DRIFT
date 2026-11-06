package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.drift.backend.maritimecontext.MaritimeEvidence;

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
	void insufficientEvidenceAbstainsBeforeTheAvailableScenarioComparisonRequiresClaims() throws Exception {
		ObjectMapper json = new ObjectMapper();
		MaritimeAiInterpretation abstention = new MaritimeAiInterpretation(
				"The supplied evidence is insufficient for a grounded interpretation.", List.of(), List.of(), List.of(), false, null);
		var assessments = mock(MaritimeAiAssessmentRepository.class);
		var service = service(json, configured(json, abstention), assessments);

		MaritimeAiAssessmentResponse response = service.generateScenarioTransient(
				inputWithAvailableScenarioComparison(syntheticEvidence()), true);

		assertThat(response.state()).isEqualTo("ABSTAINED");
		assertThat(response.validationStatus()).isEqualTo("INSUFFICIENT_MODEL_EVIDENCE");
		assertThat(response.interpretation()).isNull();
		assertThat(response.assessmentId()).isNull();
		assertThat(response.limitation()).contains("evidence was insufficient");
		verifyNoInteractions(assessments);
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

	private static MaritimeAiEvidencePackage inputWithAvailableScenarioComparison(MaritimeEvidence evidence) {
		MaritimeAiEvidencePackage base = input(evidence);
		OffsetDateTime deadline = OffsetDateTime.parse("2026-10-01T10:00:00Z");
		OffsetDateTime estimatedArrival = OffsetDateTime.parse("2026-10-01T11:00:00Z");
		var business = new MaritimeAiBusinessContext(
				new MaritimeAiBusinessContext.Deadline(MaritimeAiBusinessContext.DeadlineState.RECORDED,
						deadline, "Synthetic port", true),
				new MaritimeAiBusinessContext.Comparison(MaritimeAiBusinessContext.ComparisonState.AVAILABLE,
						com.drift.backend.shipment.DelayComparison.Outcome.EXCEEDS_DEADLINE, null,
						estimatedArrival, deadline, "Synthetic port"));
		MaritimeAiShipmentContext original = base.shipmentContext();
		var context = new MaritimeAiShipmentContext(original.transshipmentPort(), original.motherVessel(),
				original.feederVessel(), original.connectionRisk(), business, original.scenario());
		return new MaritimeAiEvidencePackage(base.bundleVersion(), base.assessmentCutoffUtc(),
				base.shipmentAssociationConfidence(), context, base.evidence());
	}

	private static MaritimeEvidence syntheticEvidence() {
		return new MaritimeEvidence("NEA_WEATHER", "SYNTHETIC_DEMONSTRATION", "weather-1", "Fictional weather context", "fixture", "", "Fixture citation",
				"2026-10-01T03:00:00Z", "2026-10-01T03:00:00Z", "2026-10-01T04:00:00Z", "FRESH", null,
				"Fictional regional weather.", null, "UNKNOWN", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true,
				"VERIFIED", "Synthetic regional context only.", null, null, null, "REGIONAL_CONTEXT");
	}
}
