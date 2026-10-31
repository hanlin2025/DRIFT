package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimecontext.SyntheticMaritimeAiSmokeFixture;

class MaritimeAiSyntheticSmokeCaptureTest {
	@Test
	void recordsParsedRejectedOutputBeforeTheAssessmentEndpointCanFail() throws Exception {
		Path output = Path.of(System.getProperty("user.dir"), "target", "cdg255-synthetic-smoke-capture-offline-test.json");
		Files.deleteIfExists(output);
		try {
			MaritimeAiClient rejectingDelegate = evidence -> new MaritimeAiClient.MaritimeAiClientResult("openai-responses", "test-model",
					"test-prompt", new MaritimeAiInterpretation("Review the grounded interpretation and limitations.",
							List.of(new MaritimeAiClaim("The documented revision occurred at 08:30.", List.of("synthetic-smoke-001"), "VERIFIED",
									"MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION", List.of("evidence:synthetic-smoke-001",
											"schedule:MOTHER:PLANNED_ARRIVAL", "schedule:FEEDER:PLANNED_DEPARTURE"), "SCHEDULE_REVIEW")),
							List.of(), List.of(), true, null), null);
			new MaritimeAiSyntheticMultiSourceIntegrationTests.SyntheticInterpretationCaptureClient(rejectingDelegate,
					new tools.jackson.databind.ObjectMapper(), output, Set.of("synthetic-smoke-001"), "cdg255.synthetic-smoke.capture.v1")
					.interpret(smokePackage());

			var capture = new tools.jackson.databind.ObjectMapper().readTree(Files.readString(output));
			assertThat(capture.path("provider").asText()).isEqualTo("openai-responses");
			assertThat(capture.path("model").asText()).isEqualTo("test-model");
			assertThat(capture.path("promptVersion").asText()).isEqualTo("test-prompt");
			assertThat(capture.path("validationStatus").asText()).isEqualTo("UNSTRUCTURED_FACTUAL_DETAIL");
			assertThat(capture.path("interpretation").path("claims").get(0).path("statement").asText())
					.isEqualTo("The documented revision occurred at 08:30.");
			assertThat(capture.toString()).doesNotContain("Authorization", "Bearer ", "not-a-key");
			assertThat(Files.getPosixFilePermissions(output)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		} finally {
			Files.deleteIfExists(output);
		}
	}

	private static MaritimeAiEvidencePackage smokePackage() {
		return new MaritimeAiEvidencePackage("cdg246.maritime-evidence-bundle.v1", "2026-10-01T00:00:00Z", "VERIFIED",
				new MaritimeAiShipmentContext("Synthetic port",
						new MaritimeAiShipmentContext.Vessel("MOTHER", "Synthetic vessel", "9000001", "PLANNED_ARRIVAL",
								OffsetDateTime.parse("2026-10-01T08:00:00+08:00")),
						new MaritimeAiShipmentContext.Vessel("FEEDER", "Synthetic feeder", "9000002", "PLANNED_DEPARTURE",
								OffsetDateTime.parse("2026-10-01T16:00:00+08:00"))),
				List.of(SyntheticMaritimeAiSmokeFixture.evidence()));
	}
}
