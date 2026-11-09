package com.drift.backend.maritimeai;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.drift.backend.shipment.DelayComparison;

/** Backend-derived signed distance between the predicted arrival and the applicable deadline. */
record ScenarioDeadlineImpact(Direction direction, long magnitudeSeconds, String magnitude) {
	enum Direction { AFTER_DEADLINE, BEFORE_DEADLINE, AT_DEADLINE }

	static ScenarioDeadlineImpact from(MaritimeAiBusinessContext.Comparison comparison) {
		if (comparison == null || comparison.estimatedArrivalAt() == null || comparison.comparedDeadline() == null)
			return null;
		OffsetDateTime estimated = comparison.estimatedArrivalAt();
		OffsetDateTime deadline = comparison.comparedDeadline();
		Duration signed = Duration.between(deadline.toInstant(), estimated.toInstant());
		if (signed.isZero()) return new ScenarioDeadlineImpact(Direction.AT_DEADLINE, 0, "0 seconds");
		if (signed.getNano() != 0) return null;
		long seconds = Math.abs(signed.getSeconds());
		Direction direction = signed.isNegative() ? Direction.BEFORE_DEADLINE : Direction.AFTER_DEADLINE;
		return new ScenarioDeadlineImpact(direction, seconds, describe(seconds));
	}

	static DelayComparison.Outcome expectedOutcome(ScenarioDeadlineImpact impact) {
		if (impact == null) return null;
		return impact.direction() == Direction.AFTER_DEADLINE
				? DelayComparison.Outcome.EXCEEDS_DEADLINE : DelayComparison.Outcome.NO_PREDICTED_IMPACT;
	}

	private static String describe(long seconds) {
		List<String> units = new ArrayList<>();
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		long remainder = seconds % 60;
		if (hours > 0) units.add(unit(hours, "hour"));
		if (minutes > 0) units.add(unit(minutes, "minute"));
		if (remainder > 0 || units.isEmpty()) units.add(unit(remainder, "second"));
		return units.size() == 1 ? units.getFirst() : String.join(" and ", units);
	}

	private static String unit(long count, String name) { return count + " " + name + (count == 1 ? "" : "s"); }
}
