package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.drift.backend.maritimecontext.MaritimeEvidence;

import tools.jackson.databind.ObjectMapper;

/** Manual one-request smoke test. It is skipped unless an owner opts in and every production gate is present. */
class MaritimeAiScenarioLiveProviderSmokeTest {
	@Test
	void worsenedShipmentASmokeFixtureIncludesBackendRiskAndDeadlineComparisonFacts() {
		MaritimeAiEvidencePackage input = scenarioInput();
		assertThat(input.bundleVersion()).isEqualTo("cdg246.maritime-evidence-bundle.v1");
		MaritimeAiShipmentContext context = input.shipmentContext();
		MaritimeAiConnectionRiskContext risk = context.connectionRisk();
		MaritimeAiBusinessContext business = context.businessContext();
		OffsetDateTime estimatedArrival = OffsetDateTime.parse("2026-10-02T08:00:00Z");
		OffsetDateTime deadline = OffsetDateTime.parse("2026-10-01T12:00:00Z");

		assertThat(context.scenario().shipmentKey()).isEqualTo("A");
		assertThat(context.scenario().shipmentReference()).isEqualTo("DEMO-CDG279-LiveSmoke-A");
		assertThat(context.scenario().stage()).isEqualTo("WORSENED");
		assertThat(context.scenario().simulationCutoffUtc()).isEqualTo("2026-10-01T08:00:00Z");
		assertThat(input.assessmentCutoffUtc()).isEqualTo("2026-10-01T08:00:00Z");
		assertThat(risk.assessed()).isTrue();
		assertThat(risk.riskLevel()).isEqualTo(com.drift.backend.shipment.ShipmentRisk.Level.HIGH);
		assertThat(risk.riskBasis()).isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE);
		assertThat(risk.plannedWindow().durationSeconds()).isEqualTo(108_000L);
		assertThat(risk.remainingWindow()).isEqualTo(new MaritimeAiConnectionRiskContext.RemainingWindow("AVAILABLE", 14_400L, null));
		assertThat(risk.arrivalEstimate().state()).isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.AVAILABLE);
		assertThat(risk.arrivalEstimate().estimatedArrivalAt()).isEqualTo(estimatedArrival);
		assertThat(business.deadline()).isEqualTo(new MaritimeAiBusinessContext.Deadline(
				MaritimeAiBusinessContext.DeadlineState.RECORDED, deadline,
				com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.PORT, true));
		assertThat(business.comparison()).isEqualTo(new MaritimeAiBusinessContext.Comparison(
				MaritimeAiBusinessContext.ComparisonState.AVAILABLE,
				com.drift.backend.shipment.DelayComparison.Outcome.EXCEEDS_DEADLINE,
				null, estimatedArrival, deadline, com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.PORT));
		assertThat(java.time.Duration.between(deadline, estimatedArrival).toHours()).isEqualTo(20);
		assertThat(input.evidence()).extracting(MaritimeEvidence::evidenceId)
				.containsExactly("cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2");
		assertThat(MaritimeAiCanonicalFactReferences.allowed(input)).contains(
				"risk:CONNECTION_SEVERITY", "risk:REMAINING_CONNECTION_WINDOW", "risk:AIS_ESTIMATED_ARRIVAL",
				"risk:ARRIVAL_ESTIMATE_FRESHNESS", "business:TRANSSHIPMENT_ARRIVAL_DEADLINE",
				"business:TRANSSHIPMENT_ARRIVAL_DEADLINE_PORT", "business:TRANSSHIPMENT_ARRIVAL_DEADLINE_APPLICABILITY",
				"comparison:DELAY_OUTCOME", "comparison:ESTIMATED_ARRIVAL", "comparison:RECORDED_DEADLINE",
				"comparison:RECORDED_TRANSSHIPMENT_PORT", "evidence:cdg279-v1-revision-1",
				"evidence:cdg279-v1-notice-2", "evidence:cdg279-v1-weather-2");
	}

	@Test
	void serializesWorsenedShipmentARiskAndDeadlineFactsIntoTheProviderInput() throws Exception {
		var body = new java.util.concurrent.atomic.AtomicReference<String>();
		ObjectMapper json = new ObjectMapper();
		var client = new ConfiguredMaritimeAiClient("openai-responses", "offline-test-model", "offline-test-key",
				"https://example.invalid/v1/responses", true, 20, 800, json, (request, requestBody) -> {
					body.set(requestBody);
					return new ConfiguredMaritimeAiClient.TransportResponse(503, "offline transport only");
				});

		client.interpret(scenarioInput());

		String boundedPromptInput = json.readTree(body.get()).at("/input/1/content/0/text").asString();
		assertThat(boundedPromptInput)
				.contains("\"bundleVersion\":\"cdg246.maritime-evidence-bundle.v1\"")
				.contains("\"riskLevel\":\"HIGH\"")
				.contains("\"riskBasis\":\"AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE\"")
				.contains("\"durationSeconds\":108000")
				.contains("\"remainingSeconds\":14400")
				.contains("\"estimatedArrivalAt\":\"2026-10-02T08:00:00Z\"")
				.contains("\"positionReceivedAt\":\"2026-10-01T08:00:00Z\"")
				.contains("\"outcome\":\"EXCEEDS_DEADLINE\"")
				.contains("\"instant\":\"2026-10-01T12:00:00Z\"")
				.contains("\"comparedDeadline\":\"2026-10-01T12:00:00Z\"")
				.contains("\"applicableToCurrentPort\":true")
				.contains("\"recordedTransshipmentPort\":\"Synthetic Multi-source Port\"")
				.contains("\"shipmentKey\":\"A\"")
				.contains("\"stage\":\"WORSENED\"")
				.contains("\"simulationCutoffUtc\":\"2026-10-01T08:00:00Z\"")
				.contains("risk:CONNECTION_SEVERITY", "risk:REMAINING_CONNECTION_WINDOW",
						"risk:AIS_ESTIMATED_ARRIVAL", "risk:ARRIVAL_ESTIMATE_FRESHNESS",
						"business:TRANSSHIPMENT_ARRIVAL_DEADLINE", "business:TRANSSHIPMENT_ARRIVAL_DEADLINE_PORT",
						"business:TRANSSHIPMENT_ARRIVAL_DEADLINE_APPLICABILITY", "comparison:DELAY_OUTCOME",
						"comparison:ESTIMATED_ARRIVAL", "comparison:RECORDED_DEADLINE",
						"comparison:RECORDED_TRANSSHIPMENT_PORT", "evidence:cdg279-v1-revision-1",
						"evidence:cdg279-v1-notice-2", "evidence:cdg279-v1-weather-2");
	}

	@Test
	void oneRealProviderResponseIsParsedAndAcceptedByTheExistingValidator() {
		String approval = System.getenv("MARITIME_AI_SCENARIO_LIVE_SMOKE_APPROVED");
		String globalEnabled = System.getenv("MARITIME_AI_ENABLED");
		String scenarioEnabled = System.getenv("MARITIME_AI_SCENARIO_ENABLED");
		String externalApproval = System.getenv("MARITIME_AI_EXTERNAL_REQUESTS_APPROVED");
		String providerName = System.getenv("MARITIME_AI_PROVIDER");
		String modelName = System.getenv("MARITIME_AI_MODEL");
		String endpoint = System.getenv("MARITIME_AI_ENDPOINT");
		String key = System.getenv("MARITIME_AI_API_KEY");
		assumeTrue("true".equalsIgnoreCase(approval), "Owner opt-in MARITIME_AI_SCENARIO_LIVE_SMOKE_APPROVED is required.");
		assumeTrue("true".equalsIgnoreCase(globalEnabled) && "true".equalsIgnoreCase(scenarioEnabled)
				&& "true".equalsIgnoreCase(externalApproval),
				"Global AI, scenario AI, and external request approval gates must all be true.");
		assumeTrue(nonblank(providerName) && nonblank(modelName) && nonblank(endpoint) && nonblank(key),
				"Configured provider, model, endpoint and key are required.");

		ObjectMapper json = new ObjectMapper();
		var client = new ConfiguredMaritimeAiClient(providerName, modelName, key, endpoint, true, 20, 800, json,
				(request, body) -> {
					var response = java.net.http.HttpClient.newHttpClient().send(request,
							java.net.http.HttpResponse.BodyHandlers.ofString());
					return new ConfiguredMaritimeAiClient.TransportResponse(response.statusCode(), response.body());
				});
		var beans = new DefaultListableBeanFactory();
		var service = new MaritimeAiAssessmentService(null, null, null, client, null,
				new MaritimeAiAssessmentHistoryAccessPolicy(), json, beans.getBeanProvider(java.time.Clock.class),
				true, false, "", "");
		MaritimeAiAssessmentResponse response = service.generateScenarioTransient(scenarioInput(), true);

		assertThat(response.assessmentId()).isNull();
		// On failure, limitation contains only the validation rule and a generated-field path, never model text.
		assertThat(response.state()).as("Safe scenario validation diagnostic: %s", response.limitation()).isEqualTo("GENERATED");
		assertThat(response.validationStatus()).as("Safe scenario validation diagnostic: %s", response.limitation()).isEqualTo("VALID");
		assertThat(response.provider()).isEqualTo(providerName);
		assertThat(response.model()).isEqualTo(modelName);
		assertThat(response.promptVersion()).isEqualTo(ConfiguredMaritimeAiClient.SCENARIO_PROMPT_VERSION);
		assertThat(response.interpretation()).isNotNull();
		assertThat(response.deterministicSummary().evidenceFacts()).allSatisfy(fact ->
				assertThat(fact.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION"));
	}

	private static boolean nonblank(String value) { return value != null && !value.isBlank(); }

	private static MaritimeAiEvidencePackage scenarioInput() {
		return ScenarioQualityStubClientTest.worsenedShipmentAInput("LiveSmoke");
	}
}
