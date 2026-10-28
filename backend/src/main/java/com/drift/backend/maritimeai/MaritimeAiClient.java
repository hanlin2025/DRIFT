package com.drift.backend.maritimeai;

public interface MaritimeAiClient {
	MaritimeAiClientResult interpret(MaritimeAiEvidencePackage evidence);

	record MaritimeAiClientResult(String provider, String model, String promptVersion,
			MaritimeAiInterpretation interpretation, String failure) {
		public static MaritimeAiClientResult unavailable(String reason) {
			return new MaritimeAiClientResult("UNCONFIGURED", "", "cdg247.evidence.v1", null, reason);
		}
	}
}
