package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class AssessmentEvidenceTest {

	private static final String REMAINING_WINDOW = "Remaining connection window";
	private static final String PLANNED_WINDOW = "Planned connection window";
	private static final String NO_POSITION = "No AIS position has been received for the mother vessel.";
	private static final String COORDINATES = "The vessel or port coordinates cannot be used.";
	private static final String MISSING_PORT = "The transshipment port is missing.";
	private static final String UNKNOWN_PORT = "The transshipment port is not on the route map.";
	private static final String UNUSABLE_SPEED = "The speed is missing or below 0.5 knots.";
	private static final String OUTDATED = "The position is more than six hours old.";
	private static final String NOT_POSITIVE = "The planned connection window is not positive.";

	private final ConnectionWindowService windows = new ConnectionWindowService();

	@Test
	void recordsTheRemainingWindowAndThePositionAndSpeedThatWereUsed() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = arrival.plusHours(6);
		ArrivalEstimate estimate = usable(arrival, "12.40", Instant.parse("2026-10-15T02:00:00Z"), false);

		assertThat(evidence(arrival.minusDays(2), feeder, estimate)).containsExactly(
				REMAINING_WINDOW,
				"6 hours",
				"Position received 2026-10-15T02:00:00Z. Speed used 12.4 knots.");
	}

	@Test
	void recordsAMissedRemainingWindowWithoutCopyingTheArrivalComparison() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T10:00:00Z");
		OffsetDateTime feeder = arrival.minusHours(2);
		ArrivalEstimate estimate = usable(arrival, "0.50", Instant.parse("2026-10-15T04:00:00Z"), false);

		assertThat(evidence(feeder.minusDays(1), feeder, estimate)).containsExactly(
				REMAINING_WINDOW,
				"The connection is expected to be missed by 2 hours.",
				"Position received 2026-10-15T04:00:00Z. Speed used 0.5 knots.")
				.noneMatch(item -> item.contains("Behind plan"));
	}

	@Test
	void recordsAMissOfNothingAsZeroSeconds() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		ArrivalEstimate estimate = usable(arrival, "8", Instant.parse("2026-10-15T01:00:00Z"), false);

		assertThat(evidence(arrival.minusHours(12), arrival, estimate)).containsExactly(
				REMAINING_WINDOW,
				"The connection is expected to be missed by 0 seconds.",
				"Position received 2026-10-15T01:00:00Z. Speed used 8 knots.");
		assertThat(ShipmentRisk.of(windows.calculate(arrival.minusHours(12), arrival), windows.remaining(arrival, estimate))
				.explanation()).isNotEqualTo("The connection is expected to be missed by 0 seconds.");
	}

	@Test
	void usesTheSameDurationTextForARemainingGapUnderOneSecond() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = arrival.plusNanos(500_000_000);
		ArrivalEstimate estimate = usable(arrival, "9.0", Instant.parse("2026-10-15T07:00:00Z"), false);

		assertThat(evidence(arrival.minusHours(3), feeder, estimate)).containsExactly(
				REMAINING_WINDOW,
				"less than 1 second",
				"Position received 2026-10-15T07:00:00Z. Speed used 9 knots.");
	}

	@Test
	void recordsAMissUnderOneSecondWithTheSameDurationText() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = arrival.minusNanos(500_000_000);
		ArrivalEstimate estimate = usable(arrival, "4", Instant.parse("2026-10-15T06:00:00Z"), false);

		assertThat(evidence(feeder.minusHours(5), feeder, estimate)).containsExactly(
				REMAINING_WINDOW,
				"The connection is expected to be missed by less than 1 second.",
				"Position received 2026-10-15T06:00:00Z. Speed used 4 knots.");
	}

	@Test
	void printsAWholeNumberSpeedWithoutScientificNotation() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = arrival.plusHours(6);
		ArrivalEstimate estimate = usable(arrival, "100.00", Instant.parse("2026-10-15T02:00:00Z"), false);

		assertThat(evidence(arrival.minusDays(1), feeder, estimate))
				.contains("Position received 2026-10-15T02:00:00Z. Speed used 100 knots.");
	}

	@Test
	void recordsThePlannedWindowAndTheReasonTheEstimateWasNotUsed() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = mother.plusDays(1);

		assertThat(evidence(mother, feeder, ArrivalEstimate.unavailable("NO_POSITION", NO_POSITION)))
				.containsExactly(PLANNED_WINDOW, "1 day", NO_POSITION)
				.noneMatch(item -> item.contains("Position received") || item.contains("Speed used"));
	}

	@Test
	void usesTheRecordedReasonRatherThanTheLongerEstimateMessage() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = mother.plusHours(28);
		ArrivalEstimate unknownPort = ArrivalEstimate.unavailable("UNKNOWN_PORT",
				"The transshipment port is not on the route map, so its coordinates cannot be used.");
		ArrivalEstimate slow = ArrivalEstimate.unavailable("UNUSABLE_SPEED",
				"The mother vessel's speed is missing or below 0.5 knots.");

		assertThat(evidence(mother, feeder, unknownPort)).containsExactly(PLANNED_WINDOW, "1 day 4 hours", UNKNOWN_PORT);
		assertThat(evidence(mother, feeder, slow)).contains(UNUSABLE_SPEED);
		assertThat(evidence(mother, feeder, ArrivalEstimate.unavailable("INVALID_COORDINATES", COORDINATES)))
				.contains(COORDINATES);
		assertThat(evidence(mother, feeder, ArrivalEstimate.unavailable("MISSING_PORT", MISSING_PORT)))
				.contains(MISSING_PORT);
	}

	@Test
	void leavesOutAPositionAndSpeedThatWereNotUsed() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = mother.plusHours(30);
		ArrivalEstimate estimate = usable(mother.plusHours(2), "11.5", Instant.parse("2026-10-14T20:00:00Z"), true);

		assertThat(windows.remaining(feeder, estimate)).isNull();
		assertThat(evidence(mother, feeder, estimate)).containsExactly(PLANNED_WINDOW, "1 day 6 hours", OUTDATED)
				.noneMatch(item -> item.contains("2026-10-14T20:00:00Z") || item.contains("11.5"));
	}

	@Test
	void recordsAPlannedWindowThatIsNotPositive() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");

		assertThat(evidence(mother, mother, ArrivalEstimate.unavailable("NO_POSITION", NO_POSITION)))
				.containsExactly(PLANNED_WINDOW, NOT_POSITIVE, NO_POSITION);
		assertThat(evidence(mother, mother.minusHours(1), ArrivalEstimate.unavailable("NO_POSITION", NO_POSITION)))
				.containsExactly(PLANNED_WINDOW, NOT_POSITIVE, NO_POSITION);
	}

	@Test
	void followsTheReadAtTheSixHourBoundary() {
		Instant received = Instant.parse("2026-10-22T00:00:00Z");
		OffsetDateTime plannedArrival = OffsetDateTime.parse("2026-10-22T18:00:00Z");
		Coordinates port = PortCatalog.load().locate("Singapore").orElseThrow();
		ArrivalFix fix = new ArrivalFix(BigDecimal.valueOf(port.latitude()), BigDecimal.valueOf(port.longitude()),
				new BigDecimal("12"), received);
		ArrivalEstimateCalculator estimates = new ArrivalEstimateCalculator(PortCatalog.load());

		ArrivalEstimate fresh = estimates.estimate(fix, "Singapore", plannedArrival,
				Clock.fixed(received.plus(Duration.ofHours(6)), ZoneOffset.UTC));
		OffsetDateTime feeder = received.plus(Duration.ofHours(6)).atOffset(ZoneOffset.UTC);
		assertThat(evidence(plannedArrival, feeder, fresh)).containsExactly(
				REMAINING_WINDOW,
				"6 hours",
				"Position received 2026-10-22T00:00:00Z. Speed used 12 knots.")
				.noneMatch(item -> item.contains("Ahead of plan") || item.contains("more than six hours"));

		ArrivalEstimate overdue = estimates.estimate(fix, "Singapore", plannedArrival,
				Clock.fixed(received.plus(Duration.ofHours(6)).plusNanos(1), ZoneOffset.UTC));
		OffsetDateTime plannedFeeder = plannedArrival.plusHours(6);
		assertThat(windows.remaining(plannedFeeder, overdue)).isNull();
		assertThat(evidence(plannedArrival, plannedFeeder, overdue)).containsExactly(
				PLANNED_WINDOW,
				"6 hours",
				OUTDATED)
				.noneMatch(item -> item.contains("2026-10-22T00:00:00Z") || item.contains("Speed used"));
		assertThat(ShipmentRisk.of(windows.calculate(plannedArrival, plannedFeeder), null).explanation())
				.isEqualTo("Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.");
	}

	@Test
	void returnsNoEvidenceWhenAPlannedTimeIsMissing() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = OffsetDateTime.parse("2026-10-16T08:00:00Z");

		assertThat(evidence(null, feeder, null)).isNull();
		assertThat(evidence(mother, null, null)).isNull();
		assertThat(evidence(null, null, null)).isNull();
	}

	@Test
	void rejectsAnEstimateReasonOutsideTheRecordedSet() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");

		assertThatThrownBy(() -> evidence(mother, mother.plusHours(4),
				ArrivalEstimate.unavailable("OTHER", "Some other reason.")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private List<String> evidence(OffsetDateTime mother, OffsetDateTime feeder, ArrivalEstimate estimate) {
		return AssessmentEvidence.of(windows.calculate(mother, feeder), windows.remaining(feeder, estimate), estimate);
	}

	private static ArrivalEstimate usable(OffsetDateTime estimatedArrival, String knots, Instant receivedAt,
			boolean possiblyOutdated) {
		return ArrivalEstimate.available(estimatedArrival, new BigDecimal(knots), Classification.BEHIND_PLAN,
				"Behind plan · estimated 2h later than planned", 7_200, 0, receivedAt, possiblyOutdated);
	}
}
