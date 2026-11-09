package com.drift.backend.maritimeai;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.drift.backend.shipment.DelayComparison;

/** Backend-derived signed distance between the predicted arrival and the applicable deadline. */
record ScenarioDeadlineImpact(Direction direction, BigDecimal magnitudeSeconds, String magnitude) {
	enum Direction { AFTER_DEADLINE, BEFORE_DEADLINE, AT_DEADLINE }

	static ScenarioDeadlineImpact from(MaritimeAiBusinessContext.Comparison comparison) {
		if (comparison == null || comparison.estimatedArrivalAt() == null || comparison.comparedDeadline() == null)
			return null;
		OffsetDateTime estimated = comparison.estimatedArrivalAt();
		OffsetDateTime deadline = comparison.comparedDeadline();
		Duration signed = Duration.between(deadline.toInstant(), estimated.toInstant());
		Duration magnitude = signed.abs();
		Direction direction = signed.isZero() ? Direction.AT_DEADLINE
				: signed.isNegative() ? Direction.BEFORE_DEADLINE : Direction.AFTER_DEADLINE;
		BigDecimal seconds = BigDecimal.valueOf(magnitude.getSeconds())
				.add(BigDecimal.valueOf(magnitude.getNano(), 9));
		seconds = new BigDecimal(seconds.stripTrailingZeros().toPlainString());
		return new ScenarioDeadlineImpact(direction, seconds, describe(magnitude));
	}

	static DelayComparison.Outcome expectedOutcome(ScenarioDeadlineImpact impact) {
		if (impact == null) return null;
		return impact.direction() == Direction.AFTER_DEADLINE
				? DelayComparison.Outcome.EXCEEDS_DEADLINE : DelayComparison.Outcome.NO_PREDICTED_IMPACT;
	}

	private static String describe(Duration duration) {
		List<String> units = new ArrayList<>();
		long hours = duration.toHours();
		Duration remainder = duration.minusHours(hours);
		long minutes = remainder.toMinutes();
		remainder = remainder.minusMinutes(minutes);
		long wholeSeconds = remainder.getSeconds();
		int nanos = remainder.getNano();
		if (hours > 0) units.add(unit(hours, "hour"));
		if (minutes > 0) units.add(unit(minutes, "minute"));
		if (wholeSeconds > 0 || nanos > 0 || units.isEmpty()) {
			BigDecimal seconds = BigDecimal.valueOf(wholeSeconds).add(BigDecimal.valueOf(nanos, 9));
			String count = seconds.stripTrailingZeros().toPlainString();
			units.add(count + (seconds.compareTo(BigDecimal.ONE) == 0 ? " second" : " seconds"));
		}
		return units.size() == 1 ? units.getFirst() : String.join(" and ", units);
	}

	private static String unit(long count, String name) { return count + " " + name + (count == 1 ? "" : "s"); }
}
