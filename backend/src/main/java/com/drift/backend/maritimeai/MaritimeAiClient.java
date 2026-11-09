package com.drift.backend.maritimeai;

public interface MaritimeAiClient {
	MaritimeAiClientResult interpret(MaritimeAiEvidencePackage evidence);

	record MaritimeAiClientResult(String provider, String model, String promptVersion,
			MaritimeAiInterpretation interpretation, String failure, String scenarioOutputText,
			String scenarioEnvelopeStatus, String scenarioFailureCode) {
		/** Source-compatible constructor for the existing V18 and v1.5 callers. */
		public MaritimeAiClientResult(String provider, String model, String promptVersion,
				MaritimeAiInterpretation interpretation, String failure) {
			this(provider, model, promptVersion, interpretation, failure, null, null, null);
		}
		public static MaritimeAiClientResult unavailable(String reason) {
			return new MaritimeAiClientResult("UNCONFIGURED", "", ConfiguredMaritimeAiClient.PROMPT_VERSION, null, reason);
		}
	}
}
