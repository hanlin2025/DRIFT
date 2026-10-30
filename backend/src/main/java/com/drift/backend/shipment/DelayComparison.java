package com.drift.backend.shipment;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether the mother-vessel transshipment-arrival estimate meets the current importer's recorded deadline. This is not an inventory, financial, or final-delivery assessment.")
public record DelayComparison(
		@Schema(description = "Completed result. Null when the comparison cannot be made.", nullable = true)
		Outcome outcome,
		@Schema(description = "Why the comparison cannot be made. Null when outcome is present.", nullable = true)
		Reason reason,
		@Schema(description = "Wording for the outcome or the single blocking reason.")
		String message,
		@Schema(description = "Estimate instant used for this comparison. Null when the estimate is unavailable.", nullable = true, example = "2026-10-28T16:00:00Z")
		OffsetDateTime estimatedArrivalAt,
		@Schema(description = "Current importer's recorded deadline. Null when no deadline is recorded.", nullable = true, example = "2026-10-28T16:00:00Z")
		OffsetDateTime deadline,
		@Schema(description = "Port recorded with that deadline. Null when no deadline is recorded.", nullable = true, example = "Singapore")
		String recordedTransshipmentPort) {

	static final String NO_IMPACT = "No predicted impact on the recorded mother-vessel transshipment-arrival deadline.";
	static final String EXCEEDS = "The predicted arrival exceeds the recorded mother-vessel transshipment-arrival deadline.";
	static final String INCOMPATIBLE = "The recorded deadline is for a different transshipment port.";
	static final String MISSING_PREDICTION_MESSAGE = "The mother-vessel transshipment-arrival estimate is unavailable.";
	static final String MISSING_CONSTRAINT_MESSAGE = "No mother-vessel transshipment-arrival deadline is recorded for the current importer.";
	static final String STALE = "The mother-vessel transshipment-arrival estimate uses an AIS position received more than six hours ago.";

	public enum Outcome {
		NO_PREDICTED_IMPACT,
		EXCEEDS_DEADLINE
	}

	public enum Reason {
		INCOMPATIBLE_MILESTONE,
		MISSING_PREDICTION,
		MISSING_CONSTRAINT,
		STALE_PREDICTION
	}

	static DelayComparison of(ArrivalEstimate estimate, TransshipmentArrivalDeadlineResponse recorded) {
		if (recorded != null && !recorded.applicable()) {
			return unavailable(Reason.INCOMPATIBLE_MILESTONE, INCOMPATIBLE, estimate, recorded);
		}
		if (estimate == null || estimate.unavailableReason() != null) {
			return unavailable(Reason.MISSING_PREDICTION, MISSING_PREDICTION_MESSAGE, estimate, recorded);
		}
		if (recorded == null) {
			return unavailable(Reason.MISSING_CONSTRAINT, MISSING_CONSTRAINT_MESSAGE, estimate, null);
		}
		if (Boolean.TRUE.equals(estimate.possiblyOutdated())) {
			return unavailable(Reason.STALE_PREDICTION, STALE, estimate, recorded);
		}
		if (estimate.estimatedArrivalAt().toInstant().compareTo(recorded.deadline().toInstant()) <= 0) {
			return completed(Outcome.NO_PREDICTED_IMPACT, NO_IMPACT, estimate, recorded);
		}
		return completed(Outcome.EXCEEDS_DEADLINE, EXCEEDS, estimate, recorded);
	}

	private static DelayComparison completed(Outcome outcome, String message, ArrivalEstimate estimate,
			TransshipmentArrivalDeadlineResponse recorded) {
		return new DelayComparison(outcome, null, message, estimate.estimatedArrivalAt(), recorded.deadline(),
				recorded.transshipmentPort());
	}

	private static DelayComparison unavailable(Reason reason, String message, ArrivalEstimate estimate,
			TransshipmentArrivalDeadlineResponse recorded) {
		OffsetDateTime estimatedArrivalAt = estimate == null || estimate.unavailableReason() != null
				? null
				: estimate.estimatedArrivalAt();
		return new DelayComparison(null, reason, message, estimatedArrivalAt,
				recorded == null ? null : recorded.deadline(),
				recorded == null ? null : recorded.transshipmentPort());
	}
}
