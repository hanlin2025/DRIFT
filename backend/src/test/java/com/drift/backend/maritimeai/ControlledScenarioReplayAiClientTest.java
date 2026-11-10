package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimecontext.MaritimeEvidence;

class ControlledScenarioReplayAiClientTest {
	private final ControlledScenarioReplayAiClient stub = new ControlledScenarioReplayAiClient();

	@Test
	void citesTheFirstScheduleSnapshotWhenTheLaterSnapshotIsNotYetRetrieved() {
		MaritimeAiClient.MaritimeAiClientResult result = stub.interpret(input(List.of(
				evidence("demo-synthetic-mpa-schedule-001"),
				evidence("demo-synthetic-notice-001"),
				evidence("demo-synthetic-nea-rainfall-001"))));

		assertThat(result.failure()).isNull();
		assertThat(result.interpretation().claims().getFirst().evidenceIds())
				.containsExactly("demo-synthetic-mpa-schedule-001");
	}

	@Test
	void citesTheLaterScheduleSnapshotWithoutDroppingTheFirst() {
		MaritimeAiClient.MaritimeAiClientResult result = stub.interpret(input(List.of(
				evidence("demo-synthetic-mpa-schedule-001"),
				evidence("demo-synthetic-notice-001"),
				evidence("demo-synthetic-nea-rainfall-001"),
				evidence("demo-synthetic-mpa-schedule-002"))));

		assertThat(result.failure()).isNull();
		assertThat(result.provider()).isEqualTo("controlled-replay-stub");
		assertThat(result.interpretation().claims().getFirst().evidenceIds())
				.containsExactly("demo-synthetic-mpa-schedule-002");
		assertThat(result.interpretation().claims().getFirst().factReferences())
				.contains("evidence:demo-synthetic-mpa-schedule-002");
	}

	@Test
	void rejectsAPartialSetAndAnyRecordOutsideTheSyntheticReplay() {
		assertThat(stub.interpret(input(List.of(evidence("demo-synthetic-mpa-schedule-001")))).failure())
				.contains("later schedule snapshot");
		MaritimeEvidence recorded = new MaritimeEvidence("MPA_ETA_REVISION", "RECORDED_REPLAY",
				"demo-synthetic-mpa-schedule-001", "Recorded", "MPA", "", "", null, null, null,
				"FRESH", "", "", "", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", true, true, "VERIFIED", "Recorded.");
		assertThat(stub.interpret(input(List.of(recorded, evidence("demo-synthetic-notice-001"),
				evidence("demo-synthetic-nea-rainfall-001")))).failure()).contains("later schedule snapshot");
	}

	private static MaritimeAiEvidencePackage input(List<MaritimeEvidence> evidence) {
		return new MaritimeAiEvidencePackage("cdg249.controlled-replay", "2026-09-30T22:45:00Z", "VERIFIED", evidence);
	}

	private static MaritimeEvidence evidence(String id) {
		return new MaritimeEvidence("MPA_ETA_REVISION", "SYNTHETIC_DEMONSTRATION", id, "Synthetic", "synthetic",
				"https://example.invalid/controlled-replay", "Fictional citation.", null, null, "2026-09-30T22:15:00Z",
				"FRESH", null, null, null, "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true,
				"VERIFIED", "Controlled fictional scenario only.");
	}
}
