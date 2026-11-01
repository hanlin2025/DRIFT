package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ArrivalEstimate.Classification;
import com.drift.backend.shipment.PortCatalog.Coordinates;

class AssessmentAssumptionsTest {

	private static final String STRAIGHT_LINE = "The estimated arrival uses a straight-line distance.";
	private static final String SPEED_RULE = "A speed of at least 0.5 knots can be used.";
	private static final String FRESHNESS_RULE = "A position up to six hours old can be used.";
	private static final String PLANNED_FALLBACK =
			"The planned connection window is used because the estimated arrival could not be used.";
	private static final String HOUR_BANDS =
			"Under 12 hours is High, under 24 hours is Medium, and 24 hours or more is Low. Exactly 12 hours is Medium and exactly 24 hours is Low. A missed or non-positive window is Critical.";

	private final ConnectionWindowService windows = new ConnectionWindowService();

	@Test
	void statesTheRulesAppliedToARemainingWindow() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = arrival.plusHours(6);
		ArrivalEstimate estimate = usable(arrival, "12.40", Instant.parse("2026-10-15T02:00:00Z"), false);

		assertThat(assumptions(arrival.minusDays(2), feeder, estimate)).containsExactly(
				STRAIGHT_LINE, SPEED_RULE, FRESHNESS_RULE, HOUR_BANDS);
	}

	@Test
	void keepsTheSameRulesWhenTheRemainingWindowIsMissed() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T10:00:00Z");
		OffsetDateTime feeder = arrival.minusHours(2);
		ArrivalEstimate missed = usable(arrival, "0.50", Instant.parse("2026-10-15T04:00:00Z"), false);
		ArrivalEstimate nothing = usable(arrival, "8", Instant.parse("2026-10-15T01:00:00Z"), false);

		assertThat(assumptions(feeder.minusDays(1), feeder, missed))
				.containsExactly(STRAIGHT_LINE, SPEED_RULE, FRESHNESS_RULE, HOUR_BANDS);
		assertThat(assumptions(arrival.minusHours(12), arrival, nothing))
				.containsExactly(STRAIGHT_LINE, SPEED_RULE, FRESHNESS_RULE, HOUR_BANDS);
	}

	@Test
	void statesOnlyTheFallbackAndHourBandsWhenThePlannedWindowIsUsed() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = mother.plusDays(1);
		ArrivalEstimate noPosition = ArrivalEstimate.unavailable("NO_POSITION",
				"No AIS position has been received for the mother vessel.");
		ArrivalEstimate outdated = usable(mother.plusHours(2), "11.5", Instant.parse("2026-10-14T20:00:00Z"), true);
		ArrivalEstimate slow = ArrivalEstimate.unavailable("UNUSABLE_SPEED",
				"The mother vessel's speed is missing or below 0.5 knots.");

		assertThat(assumptions(mother, feeder, noPosition)).containsExactly(PLANNED_FALLBACK, HOUR_BANDS);
		assertThat(assumptions(mother, mother.plusHours(30), outdated)).containsExactly(PLANNED_FALLBACK, HOUR_BANDS);
		assertThat(assumptions(mother, mother.plusHours(28), slow)).containsExactly(PLANNED_FALLBACK, HOUR_BANDS);
		assertThat(assumptions(mother, mother, noPosition)).containsExactly(PLANNED_FALLBACK, HOUR_BANDS);
	}

	@Test
	void followsTheRecordedSpeedAndFreshnessBoundaries() {
		Instant received = Instant.parse("2026-10-22T00:00:00Z");
		OffsetDateTime plannedArrival = OffsetDateTime.parse("2026-10-22T18:00:00Z");
		OffsetDateTime soon = received.plus(Duration.ofHours(6)).atOffset(ZoneOffset.UTC);
		OffsetDateTime plannedFeeder = plannedArrival.plusHours(6);
		Coordinates port = PortCatalog.load().locate("Singapore").orElseThrow();
		ArrivalEstimateCalculator estimates = new ArrivalEstimateCalculator(PortCatalog.load());
		ArrivalFix exactSpeed = fix(port, "0.5", received);
		ArrivalFix belowSpeed = fix(port, "0.49", received);
		ArrivalFix moving = fix(port, "12", received);

		ArrivalEstimate usableSpeed = estimates.estimate(exactSpeed, "Singapore", plannedArrival,
				Clock.fixed(received, ZoneOffset.UTC));
		assertThat(assumptions(plannedArrival, soon, usableSpeed))
				.containsExactly(STRAIGHT_LINE, SPEED_RULE, FRESHNESS_RULE, HOUR_BANDS);

		ArrivalEstimate slow = estimates.estimate(belowSpeed, "Singapore", plannedArrival,
				Clock.fixed(received, ZoneOffset.UTC));
		assertThat(slow.unavailableReason().code()).isEqualTo("UNUSABLE_SPEED");
		assertThat(assumptions(plannedArrival, plannedFeeder, slow)).containsExactly(PLANNED_FALLBACK, HOUR_BANDS);

		ArrivalEstimate sixHours = estimates.estimate(moving, "Singapore", plannedArrival,
				Clock.fixed(received.plus(Duration.ofHours(6)), ZoneOffset.UTC));
		assertThat(sixHours.possiblyOutdated()).isFalse();
		assertThat(assumptions(plannedArrival, soon, sixHours))
				.containsExactly(STRAIGHT_LINE, SPEED_RULE, FRESHNESS_RULE, HOUR_BANDS);

		ArrivalEstimate older = estimates.estimate(moving, "Singapore", plannedArrival,
				Clock.fixed(received.plus(Duration.ofHours(6)).plusNanos(1), ZoneOffset.UTC));
		assertThat(older.possiblyOutdated()).isTrue();
		assertThat(assumptions(plannedArrival, plannedFeeder, older)).containsExactly(PLANNED_FALLBACK, HOUR_BANDS);
		assertThat(ShipmentRisk.of(windows.calculate(plannedArrival, plannedFeeder), null).explanation())
				.isEqualTo("Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.");
	}

	@Test
	void returnsNoAssumptionsWhenAPlannedTimeIsMissing() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = OffsetDateTime.parse("2026-10-16T08:00:00Z");

		assertThat(assumptions(null, feeder, null)).isNull();
		assertThat(assumptions(mother, null, null)).isNull();
		assertThat(assumptions(null, null, null)).isNull();
	}

	private List<String> assumptions(OffsetDateTime mother, OffsetDateTime feeder, ArrivalEstimate estimate) {
		List<String> stated = AssessmentAssumptions.of(windows.calculate(mother, feeder), windows.remaining(feeder, estimate));
		if (stated != null) {
			assertThat(stated).noneMatch(item -> item.contains("Position received") || item.contains("Speed used")
					|| item.contains("probability"));
		}
		return stated;
	}

	private static ArrivalFix fix(Coordinates port, String knots, Instant receivedAt) {
		return new ArrivalFix(BigDecimal.valueOf(port.latitude()), BigDecimal.valueOf(port.longitude()),
				new BigDecimal(knots), receivedAt);
	}

	private static ArrivalEstimate usable(OffsetDateTime estimatedArrival, String knots, Instant receivedAt,
			boolean possiblyOutdated) {
		return ArrivalEstimate.available(estimatedArrival, new BigDecimal(knots), Classification.BEHIND_PLAN,
				"Behind plan · estimated 2h later than planned", 7_200, 0, receivedAt, possiblyOutdated);
	}
}
