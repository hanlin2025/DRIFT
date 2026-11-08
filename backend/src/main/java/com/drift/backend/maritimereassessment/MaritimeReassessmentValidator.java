package com.drift.backend.maritimereassessment;

/** Refuses an interpretation that upgrades identity, weather, or a berth beyond the evidence. */
final class MaritimeReassessmentValidator {

	private MaritimeReassessmentValidator() {
	}

	static String problem(MaritimeReassessmentBundle bundle, MaritimeProviderReply reply) {
		if (reply == null) {
			return "PROVIDER_MISSING: The provider returned nothing. No interpretation was stored.";
		}
		if (blank(reply.provider()) || reply.provider().length() > 100) {
			return "PROVIDER_MISSING: The provider identity is missing. No interpretation was stored.";
		}
		if (blank(reply.model()) || reply.model().length() > 200) {
			return "MODEL_MISSING: The model identity is missing. No interpretation was stored.";
		}
		if (blank(reply.promptVersion()) || reply.promptVersion().length() > 100) {
			return "PROMPT_MISSING: The prompt version is missing. No interpretation was stored.";
		}
		if (blank(reply.interpretation())) {
			return "BLANK_INTERPRETATION: The provider returned no interpretation.";
		}
		if (reply.vesselSpecificWeatherCause()) {
			return "WEATHER_CAUSATION: Regional weather was not accepted as a vessel-specific cause.";
		}
		if (reply.specificBerthClaim()) {
			return "SPECIFIC_BERTH: A general notice was not accepted as a specific berth impact.";
		}
		if ("VERIFIED_ASSOCIATION".equals(reply.associationConfidence())
				&& !"VERIFIED_ASSOCIATION".equals(bundle.associationCeiling())) {
			return "ASSOCIATION_UPGRADED: An unverified association was not stored as a verified visit.";
		}
		return null;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
