package com.drift.backend.maritimeai;

import java.time.OffsetDateTime;

import com.drift.backend.shipment.DelayComparison;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.TransshipmentArrivalDeadlineResponse;
import com.drift.backend.shipment.DelayComparison;

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

	/** Uses the fixture-owned deadline and comparison returned by the scenario calculation pipeline. */
	static MaritimeAiBusinessContext fromScenario(DelayComparison comparison, String scenarioPort) {
		if (comparison == null) return new MaritimeAiBusinessContext(
				new Deadline(DeadlineState.UNKNOWN, null, null, null),
				new Comparison(ComparisonState.UNKNOWN, null, null, null, null, null));
		boolean hasDeadline = comparison.deadline() != null;
		Deadline deadline;
		if (hasDeadline) {
			boolean incompatible = comparison.reason() == DelayComparison.Reason.INCOMPATIBLE_MILESTONE;
			Boolean applicable = incompatible ? Boolean.FALSE
					: comparison.recordedTransshipmentPort() == null || scenarioPort == null ? null
						: comparison.recordedTransshipmentPort().equalsIgnoreCase(scenarioPort);
			deadline = new Deadline(DeadlineState.RECORDED, comparison.deadline(),
					comparison.recordedTransshipmentPort(), applicable);
		} else if (comparison.reason() == DelayComparison.Reason.MISSING_CONSTRAINT) {
			deadline = new Deadline(DeadlineState.NOT_RECORDED, null, null, null);
		} else {
			deadline = new Deadline(DeadlineState.UNKNOWN, null, null, null);
		}
		Comparison comparisonContext = comparison.outcome() != null
				? new Comparison(ComparisonState.AVAILABLE, comparison.outcome(), null,
					comparison.estimatedArrivalAt(), comparison.deadline(), comparison.recordedTransshipmentPort())
				: comparison.reason() != null
					? new Comparison(ComparisonState.UNAVAILABLE, null, comparison.reason(),
						comparison.estimatedArrivalAt(), comparison.deadline(), comparison.recordedTransshipmentPort())
					: new Comparison(ComparisonState.UNKNOWN, null, null,
						comparison.estimatedArrivalAt(), comparison.deadline(), comparison.recordedTransshipmentPort());
		return new MaritimeAiBusinessContext(deadline, comparisonContext);
	}
}
