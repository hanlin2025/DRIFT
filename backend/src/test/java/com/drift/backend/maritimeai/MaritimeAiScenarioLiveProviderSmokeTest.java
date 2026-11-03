package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.drift.backend.maritimecontext.MaritimeEvidence;

import tools.jackson.databind.ObjectMapper;

/** Manual one-request smoke test. It is skipped unless an owner opts in and every production gate is present. */
class MaritimeAiScenarioLiveProviderSmokeTest {
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
		assertThat(response.state()).isEqualTo("GENERATED");
		assertThat(response.validationStatus()).isEqualTo("VALID");
		assertThat(response.provider()).isEqualTo(providerName);
		assertThat(response.model()).isEqualTo(modelName);
		assertThat(response.promptVersion()).isEqualTo(ConfiguredMaritimeAiClient.SCENARIO_PROMPT_VERSION);
		assertThat(response.interpretation()).isNotNull();
		assertThat(response.deterministicSummary().evidenceFacts()).allSatisfy(fact ->
				assertThat(fact.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION"));
	}

	private static boolean nonblank(String value) { return value != null && !value.isBlank(); }

	private static MaritimeAiEvidencePackage scenarioInput() {
		String cutoff = "2026-10-01T08:00:00Z";
		MaritimeEvidence mpa = new MaritimeEvidence("MPA_ETA_REVISION", "SYNTHETIC_DEMONSTRATION", "demo-synthetic-mpa-schedule-001",
				"Fictional expected arrival revision", "fixture", "", "Synthetic fixture citation",
				"2026-10-01T06:00:00+08:00", "2026-10-01T06:00:00+08:00", cutoff, "FRESH",
				"2026-10-01T05:00:00+08:00", "2026-10-02T08:00:00+08:00", "Fictional changed value",
				"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true, "VERIFIED",
				"Synthetic demonstration only.", "Fictional mother vessel", "MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION");
		MaritimeEvidence notice = new MaritimeEvidence("MPA_OFFICIAL_NOTICE", "SYNTHETIC_DEMONSTRATION", "demo-synthetic-notice-001",
				"Fictional port notice", "fixture", "", "Synthetic fixture citation", "2026-10-01T00:00:00+08:00",
				"2026-10-01T00:00:00+08:00", cutoff, "FRESH", null,
				"Effective 2026-10-01 06:00-12:00 SGT; fictional maintenance.", null, "COMPATIBLE", "COMPATIBLE",
				"COMPATIBLE", "COMPATIBLE", true, true, "VERIFIED", "Synthetic demonstration only.", null, null, null, "PORT_CONTEXT");
		MaritimeEvidence weather = new MaritimeEvidence("NEA_WEATHER", "SYNTHETIC_DEMONSTRATION", "demo-synthetic-nea-rainfall-001",
				"Fictional regional weather", "fixture", "", "Synthetic fixture citation", "2026-10-01T05:00:00+08:00",
				"2026-10-01T05:00:00+08:00", cutoff, "FRESH", null, "Fictional regional context.", null, "UNKNOWN",
				"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true, "VERIFIED", "Synthetic demonstration only.", null, null, null, "REGIONAL_CONTEXT");
		var context = new MaritimeAiShipmentContext("Fictional transshipment port",
				new MaritimeAiShipmentContext.Vessel("MOTHER", "Fictional mother vessel", null, "PLANNED_ARRIVAL",
						OffsetDateTime.parse("2026-10-02T08:00:00Z")),
				new MaritimeAiShipmentContext.Vessel("FEEDER", "Fictional feeder vessel", null, "PLANNED_DEPARTURE",
						OffsetDateTime.parse("2026-10-02T12:00:00Z")), null, null,
				new MaritimeAiShipmentContext.Scenario("A", "DEMO-A", "WORSENED", cutoff));
		return new MaritimeAiEvidencePackage("cdg246.maritime-evidence-bundle.v1", cutoff, "VERIFIED", context,
				List.of(mpa, notice, weather));
	}
}
