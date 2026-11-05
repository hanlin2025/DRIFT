package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.drift.backend.maritimecontext.MaritimeEvidence;

import tools.jackson.databind.ObjectMapper;

class MaritimeAiScenarioValidationDiagnosticTest {
	@Test
	void transientFailureReportsOnlyTheRejectedFieldPathAndRule() throws Exception {
		ObjectMapper json = new ObjectMapper();
		MaritimeAiInterpretation unsupported = new MaritimeAiInterpretation("The shipment is urgent.",
				List.of(new MaritimeAiClaim("The fictional regional weather context does not establish an operational impact.",
						List.of("weather-1"), "REGIONAL_CONTEXT", null, null, "REGIONAL_CONTEXT",
						List.of("evidence:weather-1"), "REGIONAL_WEATHER_CONTEXT")),
				List.of("The synthetic context does not establish a vessel-specific cause."), List.of(), true, null);
		String body = json.writeValueAsString(unsupported);
		var client = new ConfiguredMaritimeAiClient("test-json-provider", "test-model", "never-printed",
				"https://unused.invalid", true, 20, 800, json,
				(request, requestBody) -> new ConfiguredMaritimeAiClient.TransportResponse(200, body));
		var assessments = mock(MaritimeAiAssessmentRepository.class);
		var service = service(json, client, assessments);

		MaritimeAiAssessmentResponse response = service.generateScenarioTransient(input(), true);

		assertThat(response.state()).isEqualTo("FAILED");
		assertThat(response.validationStatus()).isEqualTo("UNSUPPORTED_BUSINESS_CONSTRAINT");
		assertThat(response.interpretation()).isNull();
		assertThat(response.limitation()).isEqualTo(
				"The provider response failed validation (UNSUPPORTED_BUSINESS_CONSTRAINT at summary). No interpretation is displayed.");
		assertThat(response.limitation()).doesNotContain("urgent", "weather", "weather-1", "never-printed");
		verifyNoInteractions(assessments);
	}

	@Test
	void transientFailureLocatesUnsupportedBusinessConstraintInUncertaintyWithoutExposingText() throws Exception {
		ObjectMapper json = new ObjectMapper();
		String rejectedText = "The shipment is urgent.";
		MaritimeAiInterpretation unsupported = new MaritimeAiInterpretation(
				"The supplied maritime evidence does not establish an operational impact.",
				List.of(), List.of(rejectedText), List.of(), false, null);
		String body = json.writeValueAsString(unsupported);
		var client = new ConfiguredMaritimeAiClient("test-json-provider", "test-model", "never-printed",
				"https://unused.invalid", true, 20, 800, json,
				(request, requestBody) -> new ConfiguredMaritimeAiClient.TransportResponse(200, body));
		var assessments = mock(MaritimeAiAssessmentRepository.class);
		var service = service(json, client, assessments);

		MaritimeAiAssessmentResponse response = service.generateScenarioTransient(input(), true);

		assertThat(response.state()).isEqualTo("FAILED");
		assertThat(response.validationStatus()).isEqualTo("UNSUPPORTED_BUSINESS_CONSTRAINT");
		assertThat(response.limitation()).isEqualTo(
				"The provider response failed validation (UNSUPPORTED_BUSINESS_CONSTRAINT at uncertainties[0]). No interpretation is displayed.");
		assertThat(response.limitation()).doesNotContain(rejectedText, "never-printed");
		verifyNoInteractions(assessments);
	}

	@Test
	void transientFailureLocatesUnsupportedBusinessConstraintInClaimStatement() throws Exception {
		ObjectMapper json = new ObjectMapper();
		String rejectedText = "This shipment has an urgent delivery deadline.";
		MaritimeAiInterpretation unsupported = new MaritimeAiInterpretation(
				"The supplied maritime evidence does not establish an operational impact.",
				List.of(new MaritimeAiClaim(rejectedText, List.of(), "SHIPMENT_SPECIFIC")), List.of(), List.of(), true, null);
		var assessments = mock(MaritimeAiAssessmentRepository.class);

		MaritimeAiAssessmentResponse response = generate(json, unsupported, assessments);

		assertThat(response.state()).isEqualTo("FAILED");
		assertThat(response.validationStatus()).isEqualTo("UNSUPPORTED_BUSINESS_CONSTRAINT");
		assertThat(response.limitation()).isEqualTo(
				"The provider response failed validation (UNSUPPORTED_BUSINESS_CONSTRAINT at claims[0].statement). No interpretation is displayed.");
		assertThat(response.limitation()).doesNotContain(rejectedText);
		verifyNoInteractions(assessments);
	}

	@Test
	void transientFailureLocatesFieldRejectedByValidatorWhenUrgencyUncertaintyPrecedesFollowUp() throws Exception {
		ObjectMapper json = new ObjectMapper();
		String scopedUrgency = "The supplied evidence does not establish whether this shipment is urgent.";
		String rejectedFollowUp = "This shipment has an urgent delivery deadline.";
		MaritimeAiInterpretation uncertaintyOnly = new MaritimeAiInterpretation(
				"The supplied context does not establish an operational impact.", List.of(), List.of(scopedUrgency), List.of(), false, null);
		boolean uncertaintyIsRejectedByThisValidator =
				MaritimeAiResponseValidator.claimsUnsupportedBusinessConstraint(uncertaintyOnly, input());
		String expectedLocation = uncertaintyIsRejectedByThisValidator ? "uncertainties[0]" : "followUpConsiderations[0]";
		MaritimeAiInterpretation unsupported = new MaritimeAiInterpretation(
				"The supplied maritime evidence does not establish an operational impact.",
				List.of(), List.of(scopedUrgency), List.of(rejectedFollowUp), false, null);
		var assessments = mock(MaritimeAiAssessmentRepository.class);

		MaritimeAiAssessmentResponse response = generate(json, unsupported, assessments);

		assertThat(response.state()).isEqualTo("FAILED");
		assertThat(response.validationStatus()).isEqualTo("UNSUPPORTED_BUSINESS_CONSTRAINT");
		assertThat(response.limitation()).contains("at " + expectedLocation);
		if (!uncertaintyIsRejectedByThisValidator) {
			assertThat(response.limitation()).contains("at followUpConsiderations[0]");
		}
		assertThat(response.limitation()).doesNotContain(scopedUrgency, rejectedFollowUp);
		verifyNoInteractions(assessments);
	}

	private static MaritimeAiAssessmentService service(ObjectMapper json, MaritimeAiClient client,
			MaritimeAiAssessmentRepository assessments) {
		var beans = new DefaultListableBeanFactory();
		return new MaritimeAiAssessmentService(null, null, null, client, assessments,
				new MaritimeAiAssessmentHistoryAccessPolicy(), json, beans.getBeanProvider(java.time.Clock.class),
				true, false, "", "");
	}

	private static MaritimeAiAssessmentResponse generate(ObjectMapper json, MaritimeAiInterpretation interpretation,
			MaritimeAiAssessmentRepository assessments) throws Exception {
		String body = json.writeValueAsString(interpretation);
		var client = new ConfiguredMaritimeAiClient("test-json-provider", "test-model", "never-printed",
				"https://unused.invalid", true, 20, 800, json,
				(request, requestBody) -> new ConfiguredMaritimeAiClient.TransportResponse(200, body));
		return service(json, client, assessments).generateScenarioTransient(input(), true);
	}

	private static MaritimeAiEvidencePackage input() {
		String cutoff = "2026-10-01T04:00:00Z";
		MaritimeEvidence weather = new MaritimeEvidence("NEA_WEATHER", "SYNTHETIC_DEMONSTRATION", "weather-1",
				"Fictional weather context", "fixture", "", "Fixture citation", "2026-10-01T03:00:00Z",
				"2026-10-01T03:00:00Z", cutoff, "FRESH", null, "Fictional regional weather.", null,
				"UNKNOWN", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true, "VERIFIED",
				"Synthetic regional context only.", null, null, null, "REGIONAL_CONTEXT");
		var scenario = new MaritimeAiShipmentContext.Scenario("A", "SYNTH-A", "WORSENED", cutoff);
		var context = new MaritimeAiShipmentContext("Synthetic port", null, null, null, null, scenario);
		return new MaritimeAiEvidencePackage("cdg246.maritime-evidence-bundle.v1", cutoff, "VERIFIED", context,
				List.of(weather));
	}
}
