package com.drift.backend.maritimeai;

import java.time.OffsetDateTime;

import com.drift.backend.shipment.DelayComparison;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.TransshipmentArrivalDeadlineResponse;

/** Authorized, minimized importer constraint and deterministic comparison facts for AI interpretation. */
public record MaritimeAiBusinessContext(Deadline deadline, Comparison comparison) {
	public enum DeadlineState { RECORDED, NOT_RECORDED, UNKNOWN }
	public enum ComparisonState { AVAILABLE, UNAVAILABLE, UNKNOWN }

	public record Deadline(DeadlineState state, OffsetDateTime instant, String recordedTransshipmentPort,
			Boolean applicableToCurrentPort) { }

	public record Comparison(ComparisonState state, DelayComparison.Outcome outcome,
			DelayComparison.Reason unavailableReason, OffsetDateTime estimatedArrivalAt,
			OffsetDateTime comparedDeadline, String recordedTransshipmentPort) { }

	static MaritimeAiBusinessContext from(ShipmentDetailResponse detail) {
		if (detail == null) return null;
		TransshipmentArrivalDeadlineResponse recorded = detail.transshipmentArrivalDeadline();
		Deadline deadline = recorded != null
				? new Deadline(DeadlineState.RECORDED, recorded.deadline(), recorded.transshipmentPort(), recorded.applicable())
				: new Deadline(detail.importer() == null ? DeadlineState.UNKNOWN : DeadlineState.NOT_RECORDED,
						null, null, null);
		DelayComparison comparison = detail.delayComparison();
		Comparison comparisonContext = comparison == null
				? new Comparison(ComparisonState.UNKNOWN, null, null, null, null, null)
				: new Comparison(comparison.outcome() != null ? ComparisonState.AVAILABLE
						: comparison.reason() != null ? ComparisonState.UNAVAILABLE : ComparisonState.UNKNOWN,
						comparison.outcome(), comparison.reason(), comparison.estimatedArrivalAt(), comparison.deadline(),
						comparison.recordedTransshipmentPort());
		return new MaritimeAiBusinessContext(deadline, comparisonContext);
	}
}
