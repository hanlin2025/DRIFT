package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.drift.backend.maritimeai.MaritimeAiEvidencePackage;
import tools.jackson.databind.ObjectMapper;

class VerifiedCaptureReplayClientTest {
	private static final String MPA_ID = "mpa-expected-revision-9351048-20261026T154603";
	private static final String NEA_ID = "nea-rainfall-20261027T153306";

	@TempDir Path tempDir;

	@Test
	void acceptsOnlyOwnerOnlyValidOpenAiCaptureWithExactApprovedCitations() throws Exception {
		Path capture = capture("VALID", List.of(MPA_ID, NEA_ID));
		Files.setPosixFilePermissions(capture, PosixFilePermissions.fromString("rw-------"));

		var client = replayClient(capture);
		var result = client.interpret(input());

		assertThat(result.provider()).isEqualTo("openai-responses (verified capture replay)");
		assertThat(result.interpretation().summary()).isEqualTo("Historical evidence is available for review.");
	}

	@Test
	void rejectsCaptureWithoutOwnerOnlyPermissions() throws Exception {
		Path capture = capture("VALID", List.of(MPA_ID, NEA_ID));
		Files.setPosixFilePermissions(capture, PosixFilePermissions.fromString("rw-r-----"));

		assertThatThrownBy(() -> replayClient(capture).interpret(input()))
				.isInstanceOf(IllegalStateException.class)
				.hasRootCauseMessage("Capture replay requires an owner-only capture file.");
	}

	@Test
	void rejectsCaptureWithDifferentCitationIds() throws Exception {
		Path capture = capture("VALID", List.of(MPA_ID, "unapproved-id"));
		Files.setPosixFilePermissions(capture, PosixFilePermissions.fromString("rw-------"));

		assertThatThrownBy(() -> replayClient(capture).interpret(input()))
				.isInstanceOf(IllegalStateException.class)
				.hasRootCauseMessage("Capture replay citations do not match the approved authentic bundle.");
	}

	@Test
	void rejectsCaptureThatWasNotValidatedAsValid() throws Exception {
		Path capture = capture("INVALID", List.of(MPA_ID, NEA_ID));
		Files.setPosixFilePermissions(capture, PosixFilePermissions.fromString("rw-------"));

		assertThatThrownBy(() -> replayClient(capture).interpret(input()))
				.isInstanceOf(IllegalStateException.class)
				.hasRootCauseMessage("Capture replay requires a VALID OpenAI Responses capture.");
	}

	private Path capture(String validationStatus, List<String> citationIds) throws Exception {
		Path path = tempDir.resolve("capture.json");
		String citations = citationIds.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
		Files.writeString(path, """
				{"provider":"openai-responses","validationStatus":"%s","citationIds":[%s],
				 "model":"offline-test","promptVersion":"v9",
				 "interpretation":{"summary":"Historical evidence is available for review.","claims":[],
				 "uncertainties":[],"followUpConsiderations":[],"evidenceSufficient":false,"formalRecommendationCode":null}}
				""".formatted(validationStatus, citations));
		return path;
	}

	private static AuthenticMultiSourceRagIntegrationTest.VerifiedCaptureReplayClient replayClient(Path capture) {
		return new AuthenticMultiSourceRagIntegrationTest.VerifiedCaptureReplayClient(
				new AuthenticMultiSourceRagIntegrationTest.CapturingClient(), new ObjectMapper(), capture);
	}

	private static MaritimeAiEvidencePackage input() {
		return new MaritimeAiEvidencePackage("cdg257.authentic-retrospective.v1", "2026-10-27T16:00:00Z",
				"UNVERIFIED_IMO_ONLY", List.of(evidence("MPA_ETA_REVISION", MPA_ID), evidence("NEA_WEATHER", NEA_ID)));
	}

	private static MaritimeEvidence evidence(String type, String id) {
		return new MaritimeEvidence(type, "RETROSPECTIVE_ANALYSIS", id, "Historical observation", "source", null,
				"citation", null, null, "2026-10-27T15:30:00Z", "AVAILABLE", null, null, null,
				"UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", true, true, "UNVERIFIED_IMO_ONLY", "context only");
	}
}
