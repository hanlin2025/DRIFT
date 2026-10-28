package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ArrivalEstimate.Classification;

class AssessmentExplanationTest {

	private static final String REMAINING_WINDOW = "Remaining connection window";
	private static final String PLANNED_WINDOW = "Planned connection window";
	private static final String ESTIMATED = "The estimated arrival was used.";
	private static final String LIMITED =
			"Confidence is limited because the planned connection window is being used.";
	private static final String STRAIGHT_LINE = "The estimated arrival uses a straight-line distance.";
	private static final String SPEED_RULE = "A speed of at least 0.5 knots can be used.";
	private static final String FRESHNESS_RULE = "A position up to six hours old can be used.";
	private static final String PLANNED_FALLBACK =
			"The planned connection window is used because the estimated arrival could not be used.";
	private static final String HOUR_BANDS =
			"Under 12 hours is High, under 24 hours is Medium, and 24 hours or more is Low. Exactly 12 hours is Medium and exactly 24 hours is Low. A missed or non-positive window is Critical.";
	private static final String NO_POSITION = "No AIS position has been received for the mother vessel.";
	private static final String COORDINATES = "The vessel or port coordinates cannot be used.";
	private static final String MISSING_PORT = "The transshipment port is missing.";
	private static final String UNKNOWN_PORT = "The transshipment port is not on the route map.";
	private static final String UNUSABLE_SPEED = "The speed is missing or below 0.5 knots.";
	private static final String OUTDATED = "The position is more than six hours old.";
	private static final String NOT_POSITIVE = "The planned connection window is not positive.";
	private static final String MISSING_MOTHER =
			"The assessment is unavailable because the planned mother-vessel arrival is missing.";
	private static final String MISSING_FEEDER =
			"The assessment is unavailable because the planned feeder-vessel departure is missing.";

	private final ConnectionWindowService windows = new ConnectionWindowService();

	@Test
	void explainsARemainingWindowWithThePositionAndSpeedThatWereUsed() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = arrival.plusHours(6);
		ArrivalEstimate estimate = usable(arrival, "12.40", Instant.parse("2026-10-15T02:00:00Z"), false);

		AssessmentExplanation explanation = explain(arrival.minusDays(2), feeder, estimate);

		assertThat(explanation.evidence()).containsExactly(
				REMAINING_WINDOW,
				"6 hours",
				"Position received 2026-10-15T02:00:00Z. Speed used 12.4 knots.");
		assertThat(explanation.assumptions()).containsExactly(STRAIGHT_LINE, SPEED_RULE, FRESHNESS_RULE, HOUR_BANDS);
		assertThat(explanation.confidence()).isEqualTo(ESTIMATED);
		assertThat(explanation.unavailable()).isNull();
		assertThat(explanation.evidence()).noneMatch(item -> item.contains("Behind plan") || item.contains("Ahead of plan"));
		assertThat(explanation.assumptions()).doesNotContain(PLANNED_FALLBACK);
	}

	@Test
	void keepsAMissedRemainingWindowOnTheEstimatedArrival() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T10:00:00Z");
		OffsetDateTime feeder = arrival.minusHours(2);
		ArrivalEstimate estimate = usable(arrival, "0.50", Instant.parse("2026-10-15T04:00:00Z"), false);

		AssessmentExplanation explanation = explain(feeder.minusDays(1), feeder, estimate);

		assertThat(explanation.evidence()).containsExactly(
				REMAINING_WINDOW,
				"The connection is expected to be missed by 2 hours.",
				"Position received 2026-10-15T04:00:00Z. Speed used 0.5 knots.");
		assertThat(explanation.confidence()).isEqualTo(ESTIMATED);
		assertThat(explanation.assumptions()).contains(STRAIGHT_LINE, HOUR_BANDS);
		assertThat(explanation.unavailable()).isNull();
	}

	@Test
	void explainsAMissOfNothingAsZeroSeconds() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		ArrivalEstimate estimate = usable(arrival, "8", Instant.parse("2026-10-15T01:00:00Z"), false);

		AssessmentExplanation explanation = explain(arrival.minusHours(12), arrival, estimate);

		assertThat(explanation.evidence()).contains("The connection is expected to be missed by 0 seconds.");
		assertThat(explanation.confidence()).isEqualTo(ESTIMATED);
	}

	@Test
	void usesThePlannedDurationTextForARemainingGapUnderOneSecond() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = arrival.plusNanos(500_000_000);
		ArrivalEstimate estimate = usable(arrival, "9.0", Instant.parse("2026-10-15T07:00:00Z"), false);

		AssessmentExplanation explanation = explain(arrival.minusHours(3), feeder, estimate);

		assertThat(explanation.evidence()).contains("less than 1 second");
		assertThat(explanation.confidence()).isEqualTo(ESTIMATED);
	}

	@Test
	void explainsThePlannedWindowWhenNoPositionWasReceived() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = mother.plusDays(1);
		ArrivalEstimate estimate = ArrivalEstimate.unavailable("NO_POSITION", NO_POSITION);

		AssessmentExplanation explanation = explain(mother, feeder, estimate);

		assertThat(explanation.evidence()).containsExactly(PLANNED_WINDOW, "1 day", NO_POSITION);
		assertThat(explanation.assumptions()).containsExactly(PLANNED_FALLBACK, HOUR_BANDS);
		assertThat(explanation.confidence()).isEqualTo(LIMITED);
		assertThat(explanation.unavailable()).isNull();
		assertThat(explanation.evidence()).noneMatch(item -> item.contains("Position received") || item.contains("Speed used"));
		assertThat(explanation.assumptions()).doesNotContain(STRAIGHT_LINE, SPEED_RULE, FRESHNESS_RULE);
	}

	@Test
	void usesTheRecordedReasonRatherThanTheLongerEstimateMessage() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = mother.plusHours(28);
		ArrivalEstimate unknownPort = ArrivalEstimate.unavailable("UNKNOWN_PORT",
				"The transshipment port is not on the route map, so its coordinates cannot be used.");
		ArrivalEstimate slow = ArrivalEstimate.unavailable("UNUSABLE_SPEED",
				"The mother vessel's speed is missing or below 0.5 knots.");
		ArrivalEstimate coordinates = ArrivalEstimate.unavailable("INVALID_COORDINATES", COORDINATES);
		ArrivalEstimate missingPort = ArrivalEstimate.unavailable("MISSING_PORT", MISSING_PORT);

		assertThat(explain(mother, feeder, unknownPort).evidence()).containsExactly(PLANNED_WINDOW, "1 day 4 hours", UNKNOWN_PORT);
		assertThat(explain(mother, feeder, slow).evidence()).contains(UNUSABLE_SPEED);
		assertThat(explain(mother, feeder, coordinates).evidence()).contains(COORDINATES);
		assertThat(explain(mother, feeder, missingPort).evidence()).contains(MISSING_PORT);
		assertThat(explain(mother, feeder, unknownPort).confidence()).isEqualTo(LIMITED);
	}

	@Test
	void explainsThePlannedWindowWhenThePositionIsOlderThanSixHours() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = mother.plusHours(30);
		OffsetDateTime estimatedArrival = mother.plusHours(2);
		ArrivalEstimate estimate = usable(estimatedArrival, "11.5", Instant.parse("2026-10-14T20:00:00Z"), true);

		AssessmentExplanation explanation = explain(mother, feeder, estimate);

		assertThat(windows.remaining(feeder, estimate)).isNull();
		assertThat(explanation.evidence()).containsExactly(PLANNED_WINDOW, "1 day 6 hours", OUTDATED);
		assertThat(explanation.evidence()).noneMatch(item -> item.contains("2026-10-14T20:00:00Z") || item.contains("11.5"));
		assertThat(explanation.confidence()).isEqualTo(LIMITED);
		assertThat(explanation.assumptions()).containsExactly(PLANNED_FALLBACK, HOUR_BANDS);
	}

	@Test
	void explainsAPlannedWindowThatIsNotPositive() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		ArrivalEstimate estimate = ArrivalEstimate.unavailable("NO_POSITION", NO_POSITION);

		AssessmentExplanation explanation = explain(mother, mother, estimate);

		assertThat(explanation.evidence()).containsExactly(PLANNED_WINDOW, NOT_POSITIVE, NO_POSITION);
		assertThat(explanation.confidence()).isEqualTo(LIMITED);
		assertThat(explanation.assumptions()).contains(HOUR_BANDS);
	}

	@Test
	void namesAMissingMotherArrivalWithoutALimitedConfidence() {
		OffsetDateTime feeder = OffsetDateTime.parse("2026-10-16T08:00:00Z");

		AssessmentExplanation explanation = explain(null, feeder, null);

		assertThat(explanation.unavailable()).containsExactly(MISSING_MOTHER);
		assertThat(explanation.evidence()).isNull();
		assertThat(explanation.assumptions()).isNull();
		assertThat(explanation.confidence()).isNull();
	}

	@Test
	void namesAMissingFeederDepartureWithoutALimitedConfidence() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");

		AssessmentExplanation explanation = explain(mother, null, null);

		assertThat(explanation.unavailable()).containsExactly(MISSING_FEEDER);
		assertThat(explanation.confidence()).isNull();
		assertThat(explanation.evidence()).isNull();
	}

	@Test
	void namesAMissingMotherArrivalBeforeAMissingFeederDeparture() {
		AssessmentExplanation explanation = explain(null, null, null);

		assertThat(explanation.unavailable()).containsExactly(MISSING_MOTHER, MISSING_FEEDER);
		assertThat(explanation.confidence()).isNull();
	}

	@Test
	void rejectsAnEstimateReasonOutsideTheRecordedSet() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");

		assertThatThrownBy(() -> explain(mother, mother.plusHours(4),
				ArrivalEstimate.unavailable("OTHER", "Some other reason.")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private AssessmentExplanation explain(OffsetDateTime mother, OffsetDateTime feeder, ArrivalEstimate estimate) {
		ConnectionWindowCalculation planned = windows.calculate(mother, feeder);
		return AssessmentExplanation.of(planned, windows.remaining(feeder, estimate), estimate, mother, feeder);
	}

	private static ArrivalEstimate usable(OffsetDateTime estimatedArrival, String knots, Instant receivedAt,
			boolean possiblyOutdated) {
		return ArrivalEstimate.available(estimatedArrival, new BigDecimal(knots), Classification.BEHIND_PLAN,
				"Behind plan · estimated 2h later than planned", 7_200, 0, receivedAt, possiblyOutdated);
	}
}
