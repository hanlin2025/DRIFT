package com.drift.backend.maritimereassessment;

/** One provider response. It is not an assessment until grounding validation accepts it. */
public record MaritimeProviderReply(
		String provider,
		String model,
		String promptVersion,
		String interpretation,
		String associationConfidence,
		boolean vesselSpecificWeatherCause,
		boolean specificBerthClaim) {
}
