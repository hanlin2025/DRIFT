package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.PortCatalog.Coordinates;
import com.drift.backend.shipment.ShipmentRisk.Level;
import com.drift.backend.shipment.ShipmentRiskContribution.Code;

class ShipmentRiskContributionsTest {

	private static final Instant RECEIVED = Instant.parse("2026-10-26T04:00:00Z");
	private static final OffsetDateTime PLANNED = OffsetDateTime.parse("2026-10-26T12:00:00Z");
	private static final String NO_POSITION = "No AIS position has been received for the mother vessel.";
	private static final String INVALID_COORDINATES = "The vessel or port coordinates cannot be used.";
	private static final String MISSING_PORT = "The transshipment port is missing.";
	private static final String UNKNOWN_PORT = "The transshipment port is not on the route map, so its coordinates cannot be used.";
	private static final String UNUSABLE_SPEED = "The mother vessel's speed is missing or below 0.5 knots.";

	private final ConnectionWindowService windows = new ConnectionWindowService();
	private final ArrivalEstimateCalculator estimates = new ArrivalEstimateCalculator(PortCatalog.load());

	@Test
	void namesTheRemainingWindowAndItsDurationWithoutRestatingTheHourBand() {
		assertRemaining(Duration.ofHours(12).minusSeconds(1), Level.HIGH,
				"Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
				"11 hours 59 minutes 59 seconds");
		assertRemaining(Duration.ofHours(12), Level.MEDIUM,
				"Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
				"12 hours");
		assertRemaining(Duration.ofHours(24).minusSeconds(1), Level.MEDIUM,
				"Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
				"23 hours 59 minutes 59 seconds");
		assertRemaining(Duration.ofHours(24), Level.LOW,
				"At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
				"1 day");
		assertRemaining(Duration.ofMillis(500), Level.HIGH,
				"Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.",
				"less than 1 second");
	}

	@Test
	void namesAMissOfZeroSecondsAndALongerMissWithoutReplacingTheExplanation() {
		assertMiss("0 seconds", 0,
				"The connection is expected to be missed. The mother vessel's estimated arrival is at the planned feeder departure.");
		assertMiss("less than 1 second", 0, "The connection is expected to be missed by less than 1 second.");
		assertMiss("2 hours", 7_200, "The connection is expected to be missed by 2 hours.");
	}

	@Test
	void usesThePlannedWindowWhenTheEstimateCannotBeUsedAndKeepsEachReasonDistinct() {
		assertFallback(ArrivalEstimate.unavailable("NO_POSITION", NO_POSITION), Duration.ofHours(6), Level.HIGH,
				"Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.",
				"6 hours", NO_POSITION);
		assertFallback(ArrivalEstimate.unavailable("INVALID_COORDINATES", INVALID_COORDINATES), Duration.ofHours(12),
				Level.MEDIUM,
				"Less than 24 hours to transfer cargo between vessels. The planned connection window is being used.",
				"12 hours", INVALID_COORDINATES);
		assertFallback(ArrivalEstimate.unavailable("MISSING_PORT", MISSING_PORT), Duration.ofHours(24), Level.LOW,
				"At least 24 hours to transfer cargo between vessels. The planned connection window is being used.",
				"1 day", MISSING_PORT);
		assertFallback(ArrivalEstimate.unavailable("UNKNOWN_PORT", UNKNOWN_PORT), Duration.ofHours(2), Level.HIGH,
				"Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.",
				"2 hours", UNKNOWN_PORT);
		assertFallback(ArrivalEstimate.unavailable("UNUSABLE_SPEED", UNUSABLE_SPEED), Duration.ofHours(18), Level.MEDIUM,
				"Less than 24 hours to transfer cargo between vessels. The planned connection window is being used.",
				"18 hours", UNUSABLE_SPEED);
		assertFallback(outdated(), Duration.ofHours(6), Level.HIGH,
				"Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.",
				"6 hours", ShipmentRiskContributions.OUTDATED);
	}

	@Test
	void anEstimateThatIsExactlySixHoursOldOrExactlyHalfAKnotStaysOnTheRemainingWindow() {
		ArrivalEstimate exactAge = estimate(Clock.fixed(RECEIVED.plus(Duration.ofHours(6)), ZoneOffset.UTC), "0.5");
		assertThat(exactAge.possiblyOutdated()).isFalse();
		RemainingConnectionWindow remaining = windows.remaining(RECEIVED.plus(Duration.ofHours(6)).atOffset(ZoneOffset.UTC),
				exactAge);
		ShipmentRiskContributions contributions = ShipmentRiskContributions.of(planned(Duration.ofHours(48)), remaining,
				exactAge, PLANNED, PLANNED.plusHours(48));

		assertThat(contributions.contributions()).extracting(ShipmentRiskContribution::code)
				.containsExactly(Code.WINDOW_IN_USE, Code.REMAINING_DURATION);
		assertThat(contributions.contributions().get(1).value()).isEqualTo("6 hours");
		assertThat(values(contributions)).doesNotContain(ShipmentRiskContributions.OUTDATED, UNUSABLE_SPEED);
	}

	@Test
	void aPositionOneNanosecondOlderThanSixHoursNamesThatReasonAlone() {
		ArrivalEstimate overdue = estimate(Clock.fixed(RECEIVED.plus(Duration.ofHours(6)).plusNanos(1), ZoneOffset.UTC), "12");
		assertThat(overdue.possiblyOutdated()).isTrue();
		assertThat(windows.remaining(PLANNED, overdue)).isNull();

		ShipmentRiskContributions contributions = ShipmentRiskContributions.of(planned(Duration.ofHours(6)), null, overdue,
				PLANNED, PLANNED.plusHours(6));

		assertThat(contributions.contributions()).extracting(ShipmentRiskContribution::value)
				.containsExactly(ShipmentRiskContributions.PLANNED_WINDOW, "6 hours", ShipmentRiskContributions.OUTDATED);
		assertThat(values(contributions)).doesNotContain(overdue.estimatedArrivalAt().toString(), overdue.limitation(),
				overdue.speedOverGroundKnots().toString(), overdue.difference(), overdue.lastReceivedAt().toString());
	}

	@Test
	void anInvalidPlannedWindowIsCriticalAndDoesNotInventADuration() {
		ConnectionWindowCalculation invalid = windows.calculate(PLANNED, PLANNED);
		ArrivalEstimate unavailable = ArrivalEstimate.unavailable("NO_POSITION", NO_POSITION);
		ShipmentRisk risk = ShipmentRisk.of(invalid, null);
		ShipmentRiskContributions contributions = ShipmentRiskContributions.of(invalid, null, unavailable, PLANNED, PLANNED);

		assertThat(risk.level()).isEqualTo(Level.CRITICAL);
		assertThat(risk.explanation()).isEqualTo(
				"The feeder vessel is planned to depart at or before the mother vessel's arrival, leaving no time to transfer cargo. The planned connection window is being used.");
		assertThat(contributions.contributions()).extracting(ShipmentRiskContribution::code, ShipmentRiskContribution::value)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(Code.WINDOW_IN_USE, ShipmentRiskContributions.PLANNED_WINDOW),
						org.assertj.core.groups.Tuple.tuple(Code.PLANNED_WINDOW, ShipmentRiskContributions.NOT_POSITIVE),
						org.assertj.core.groups.Tuple.tuple(Code.ESTIMATE_NOT_USED, NO_POSITION));
		assertThat(values(contributions)).doesNotContain(risk.explanation());
	}

	@Test
	void aMissingPlannedTimeIsNotAssessedAndIsNotAnUnavailableEstimate() {
		assertThat(notAssessed(null, PLANNED).notAssessed()).isEqualTo(ShipmentRiskContributions.MISSING_MOTHER);
		assertThat(notAssessed(PLANNED, null).notAssessed()).isEqualTo(ShipmentRiskContributions.MISSING_FEEDER);
		assertThat(notAssessed(null, null).notAssessed()).isEqualTo(ShipmentRiskContributions.MISSING_BOTH);
		for (ShipmentRiskContributions contributions : new ShipmentRiskContributions[] {
				notAssessed(null, PLANNED), notAssessed(PLANNED, null), notAssessed(null, null) }) {
			assertThat(contributions.assessed()).isFalse();
			assertThat(contributions.contributions()).isEmpty();
			assertThat(contributions.notAssessed()).doesNotContain("AIS", "0.5 knots", "six hours");
		}
	}

	@Test
	void theRemainingWindowWinsWhenThePlannedWindowDisagrees() {
		ConnectionWindowCalculation planned = planned(Duration.ofHours(48));
		RemainingConnectionWindow remaining = windows.remaining(RECEIVED.plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC),
				fresh("12"));
		ShipmentRiskContributions contributions = ShipmentRiskContributions.of(planned, remaining, fresh("12"), PLANNED,
				PLANNED.plusHours(48));

		assertThat(ShipmentRisk.of(planned, remaining).level()).isEqualTo(Level.HIGH);
		assertThat(contributions.contributions()).extracting(ShipmentRiskContribution::value)
				.containsExactly(ShipmentRiskContributions.REMAINING_WINDOW, "2 hours");
	}

	@Test
	void requiresThePlannedCalculationAndAReasonForTheFallback() {
		assertThatThrownBy(() -> ShipmentRiskContributions.of(null, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ShipmentRiskContributions.of(planned(Duration.ofHours(6)), null, null, PLANNED,
				PLANNED.plusHours(6))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShipmentRiskContributions(false, "Connection risk is not assessed.",
				java.util.List.of(ShipmentRiskContribution.window(ShipmentRiskContributions.REMAINING_WINDOW))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private void assertRemaining(Duration remaining, Level level, String explanation, String duration) {
		ArrivalEstimate fresh = fresh("12");
		RemainingConnectionWindow window = windows.remaining(RECEIVED.plus(remaining).atOffset(ZoneOffset.UTC), fresh);
		ConnectionWindowCalculation planned = planned(Duration.ofHours(48));
		ShipmentRisk risk = ShipmentRisk.of(planned, window);
		ShipmentRiskContributions contributions = ShipmentRiskContributions.of(planned, window, fresh, PLANNED,
				PLANNED.plusHours(48));

		assertThat(risk).isEqualTo(new ShipmentRisk(level, explanation));
		assertThat(contributions.assessed()).isTrue();
		assertThat(contributions.notAssessed()).isNull();
		assertThat(contributions.contributions()).extracting(ShipmentRiskContribution::code, ShipmentRiskContribution::label,
				ShipmentRiskContribution::value).containsExactly(
						org.assertj.core.groups.Tuple.tuple(Code.WINDOW_IN_USE, "Window in use",
								ShipmentRiskContributions.REMAINING_WINDOW),
						org.assertj.core.groups.Tuple.tuple(Code.REMAINING_DURATION, "Remaining duration", duration));
		assertThat(values(contributions)).doesNotContain(explanation, "Under 12 hours", "Under 24 hours");
	}

	private void assertMiss(String missedBy, long seconds, String explanation) {
		RemainingConnectionWindow window = RemainingConnectionWindow.missed(missedBy, seconds);
		ShipmentRisk risk = ShipmentRisk.of(planned(Duration.ofHours(48)), window);
		ShipmentRiskContributions contributions = ShipmentRiskContributions.of(planned(Duration.ofHours(48)), window,
				fresh("12"), PLANNED, PLANNED.plusHours(48));

		assertThat(risk.level()).isEqualTo(Level.CRITICAL);
		assertThat(risk.explanation()).isEqualTo(explanation);
		assertThat(contributions.contributions()).extracting(ShipmentRiskContribution::code, ShipmentRiskContribution::value)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(Code.WINDOW_IN_USE, ShipmentRiskContributions.REMAINING_WINDOW),
						org.assertj.core.groups.Tuple.tuple(Code.MISSED_BY, missedBy));
		assertThat(values(contributions)).doesNotContain(explanation);
	}

	private void assertFallback(ArrivalEstimate arrival, Duration plannedDuration, Level level, String explanation,
			String duration, String reason) {
		ConnectionWindowCalculation planned = planned(plannedDuration);
		assertThat(windows.remaining(PLANNED.plus(plannedDuration), arrival)).isNull();
		ShipmentRisk risk = ShipmentRisk.of(planned, null);
		ShipmentRiskContributions contributions = ShipmentRiskContributions.of(planned, null, arrival, PLANNED,
				PLANNED.plus(plannedDuration));

		assertThat(risk).isEqualTo(new ShipmentRisk(level, explanation));
		assertThat(contributions.contributions()).extracting(ShipmentRiskContribution::code, ShipmentRiskContribution::value)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(Code.WINDOW_IN_USE, ShipmentRiskContributions.PLANNED_WINDOW),
						org.assertj.core.groups.Tuple.tuple(Code.PLANNED_DURATION, duration),
						org.assertj.core.groups.Tuple.tuple(Code.ESTIMATE_NOT_USED, reason));
		assertThat(values(contributions)).doesNotContain(explanation);
	}

	private ShipmentRiskContributions notAssessed(OffsetDateTime mother, OffsetDateTime feeder) {
		ConnectionWindowCalculation planned = windows.calculate(mother, feeder);
		assertThat(ShipmentRisk.of(planned, null)).isNull();
		return ShipmentRiskContributions.of(planned, null, null, mother, feeder);
	}

	private ConnectionWindowCalculation planned(Duration duration) {
		return windows.calculate(PLANNED, PLANNED.plus(duration));
	}

	private ArrivalEstimate fresh(String speed) {
		return estimate(Clock.fixed(RECEIVED, ZoneOffset.UTC), speed);
	}

	private ArrivalEstimate outdated() {
		return estimate(Clock.fixed(RECEIVED.plus(Duration.ofHours(6)).plusNanos(1), ZoneOffset.UTC), "12");
	}

	private ArrivalEstimate estimate(Clock clock, String speed) {
		Coordinates port = PortCatalog.load().locate("Singapore").orElseThrow();
		ArrivalFix fix = new ArrivalFix(BigDecimal.valueOf(port.latitude()), BigDecimal.valueOf(port.longitude()),
				new BigDecimal(speed), RECEIVED);
		return estimates.estimate(fix, "Singapore", PLANNED, clock);
	}

	private static java.util.List<String> values(ShipmentRiskContributions contributions) {
		return contributions.contributions().stream().map(ShipmentRiskContribution::value).toList();
	}
}
