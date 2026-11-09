package com.drift.backend.maritimeai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.ObjectMapper;

/** Offline-only formatter for the authenticated scenario-endpoint quality matrix. */
public final class ScenarioQualityStubClient extends ConfiguredMaritimeAiClient {
	private final List<MaritimeAiEvidencePackage> scenarioInputs = new ArrayList<>();
	private final SyntheticDisruptionAiClient ordinaryClient;
	private final ConfiguredMaritimeAiClient scenarioClient;

	public ScenarioQualityStubClient() {
		this(new SyntheticDisruptionAiClient());
	}

	public ScenarioQualityStubClient(SyntheticDisruptionAiClient ordinaryClient) {
		super("openai-responses", "gpt-4.1-mini", "offline-test-key", "https://scenario.invalid/responses", true, 20, 800,
				new ObjectMapper(), (request, body) -> {
					throw new AssertionError("The scenario fixture must use its injected Responses transport.");
				});
		this.ordinaryClient = ordinaryClient;
		this.scenarioClient = new ConfiguredMaritimeAiClient("openai-responses", "gpt-4.1-mini", "offline-test-key",
				"https://scenario.invalid/responses", true, 20, 800, new ObjectMapper(), (request, body) -> {
					String output = jsonForLastInput.get();
					if (output == null) throw new AssertionError("Scenario fixture output was not prepared.");
					return new ConfiguredMaritimeAiClient.TransportResponse(200, responsesEnvelope(output));
				});
	}

	@Override
	public synchronized MaritimeAiClientResult interpret(MaritimeAiEvidencePackage input) {
		if (input.shipmentContext() == null || input.shipmentContext().scenario() == null)
			return ordinaryClient.interpret(input);
		scenarioInputs.add(input);
		jsonForLastInput.set(scenarioOutput(input));
		MaritimeAiClientResult response = scenarioClient.interpret(input);
		return new MaritimeAiClientResult("offline-scenario-quality-stub", "deterministic-fixture",
				response.promptVersion(), response.interpretation(), response.failure(), response.scenarioOutputText(),
				response.scenarioEnvelopeStatus(), response.scenarioFailureCode());
	}

	public synchronized List<MaritimeAiEvidencePackage> scenarioInputs() {
		return List.copyOf(scenarioInputs);
	}

	public synchronized void clearScenarioInputs() {
		scenarioInputs.clear();
	}

	private final ThreadLocal<String> jsonForLastInput = new ThreadLocal<>();

	private static String scenarioOutput(MaritimeAiEvidencePackage input) {
		var context = Objects.requireNonNull(input.shipmentContext());
		var risk = Objects.requireNonNull(context.connectionRisk());
		var revision = input.evidence().stream()
				.filter(item -> "MPA_ETA_REVISION".equals(item.sourceType())
						&& "MOTHER".equals(item.vesselRole()) && "EXPECTED_ARRIVAL".equals(item.observationType()))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("Scenario fixture revision is required."));

		var business = Objects.requireNonNull(context.businessContext());
		var comparison = Objects.requireNonNull(business.comparison());
		var riskReview = MaritimeAiResponseValidator.expectedRiskReview(risk);
		String riskStatement = ScenarioMaritimeAiProse.riskAlternatives(risk.riskLevel(), riskReview.selectedWindowState()).getFirst();
		String deadlineStatement = ScenarioMaritimeAiProse.deadlineAlternatives(
				context.scenario().shipmentKey(), comparison).getFirst();
		String evidenceRef = "evidence:" + revision.evidenceId();
		Map<String, Object> riskPayload = Map.of("evidenceRef", evidenceRef,
				"riskReview", Map.of("severity", riskReview.severity().name(),
						"selectedWindowBasis", riskReview.selectedWindowBasis().name(),
						"selectedWindowState", riskReview.selectedWindowState().name(),
						"estimateFreshness", riskReview.estimateFreshness().name()),
				"statement", riskStatement);
		Map<String, Object> deadlinePayload = Map.of("evidenceRef", evidenceRef,
				"outcome", comparison.outcome().name(), "statement", deadlineStatement);
		Map<String, Object> assessment = Map.of("risk", riskPayload, "deadline", deadlinePayload,
				"uncertainty", ScenarioMaritimeAiProse.uncertaintyAlternatives().getFirst(),
				"etaCheck", ScenarioMaritimeAiProse.etaCheckAlternatives().getFirst(),
				"deadlineCheck", ScenarioMaritimeAiProse.deadlineCheckAlternatives().getFirst());
		try { return new ObjectMapper().writeValueAsString(Map.of("assessment", assessment)); }
		catch (Exception failure) { throw new IllegalStateException("Scenario fixture could not be serialized.", failure); }
	}

	static String fixtureOutputText(MaritimeAiEvidencePackage input) { return scenarioOutput(input); }
	static String fixtureResponsesEnvelope(String outputText) { return responsesEnvelope(outputText); }

	private static String responsesEnvelope(String outputText) {
		try {
			return new ObjectMapper().writeValueAsString(Map.of("id", "resp_scenario_test", "status", "completed",
					"output", List.of(Map.of("id", "msg_scenario_test", "type", "message", "role", "assistant",
							"status", "completed", "content", List.of(Map.of("type", "output_text", "text", outputText))))));
		} catch (Exception failure) { throw new IllegalStateException("Scenario response fixture could not be serialized.", failure); }
	}
}
