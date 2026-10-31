package com.drift.backend.maritimeai;

public interface MaritimeAiClient {
	MaritimeAiClientResult interpret(MaritimeAiEvidencePackage evidence);

	record MaritimeAiClientResult(String provider, String model, String promptVersion,
			MaritimeAiInterpretation interpretation, String failure) {
		public static MaritimeAiClientResult unavailable(String reason) {
			return new MaritimeAiClientResult("UNCONFIGURED", "", ConfiguredMaritimeAiClient.PROMPT_VERSION, null, reason);
		}
	}
}
