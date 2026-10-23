package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ArrivalEstimate.Classification;
import com.drift.backend.shipment.ConnectionWindowCalculation.Status;

class ConnectionWindowServiceTest {

	private final ConnectionWindowService windows = new ConnectionWindowService();

	@Test
	void calculatesTheTimeAvailableBetweenMotherArrivalAndFeederDeparture() {
		ConnectionWindowCalculation window = windows.calculate(
				OffsetDateTime.parse("2026-10-15T08:00:00+08:00"),
				OffsetDateTime.parse("2026-10-16T12:00:00+08:00"));

		assertThat(window.status()).isEqualTo(Status.AVAILABLE);
		assertThat(window.window().duration()).isEqualTo("1 day 4 hours");
		assertThat(window.window().totalSeconds()).isEqualTo(Duration.ofHours(28).toSeconds());
	}

	@Test
	void usesTheInstantsSoOffsetsAreNotComparedAsLocalClockTimes() {
		ConnectionWindowCalculation window = windows.calculate(
				OffsetDateTime.parse("2026-10-15T20:00:00+08:00"),
				OffsetDateTime.parse("2026-10-16T02:30:00Z"));

		assertThat(window.status()).isEqualTo(Status.AVAILABLE);
		assertThat(window.window().duration()).isEqualTo("14 hours 30 minutes");
		assertThat(window.window().totalSeconds()).isEqualTo(Duration.ofHours(14).plusMinutes(30).toSeconds());
	}

	@Test
	void describesSingleAndPluralUnitsAndAGapUnderOneSecond() {
		assertThat(duration("2026-10-15T08:00:00Z", "2026-10-17T08:00:00Z")).isEqualTo("2 days");
		assertThat(duration("2026-10-15T08:00:00Z", "2026-10-15T09:00:00Z")).isEqualTo("1 hour");
		assertThat(duration("2026-10-15T08:00:00Z", "2026-10-15T10:01:00Z")).isEqualTo("2 hours 1 minute");
		assertThat(duration("2026-10-15T08:00:00Z", "2026-10-15T08:01:01Z")).isEqualTo("1 minute 1 second");
		assertThat(duration("2026-10-15T08:00:00Z", "2026-10-15T08:00:01Z")).isEqualTo("1 second");

		ConnectionWindowCalculation brief = windows.calculate(
				OffsetDateTime.parse("2026-10-15T08:00:00Z"),
				OffsetDateTime.parse("2026-10-15T08:00:00.500Z"));
		assertThat(brief.status()).isEqualTo(Status.AVAILABLE);
		assertThat(brief.window().duration()).isEqualTo("less than 1 second");
		assertThat(brief.window().totalSeconds()).isZero();
	}

	@Test
	void recalculatesWhenEitherPlannedTimeChanges() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime departure = OffsetDateTime.parse("2026-10-15T09:00:00Z");

		assertThat(duration(arrival, departure)).isEqualTo("1 hour");
		assertThat(duration(arrival, departure.plusHours(2))).isEqualTo("3 hours");
		assertThat(duration(arrival.plusMinutes(30), departure.plusHours(2))).isEqualTo("2 hours 30 minutes");
	}

	@Test
	void identifiesAMissingTimeAsIncompleteAndClearsTheDuration() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime departure = OffsetDateTime.parse("2026-10-15T09:00:00Z");

		assertThat(windows.calculate(null, departure).status()).isEqualTo(Status.INCOMPLETE);
		assertThat(windows.calculate(arrival, null).status()).isEqualTo(Status.INCOMPLETE);
		assertThat(windows.calculate(null, null).status()).isEqualTo(Status.INCOMPLETE);
		assertThat(windows.calculate(null, departure).window()).isNull();
		assertThat(windows.calculate(arrival, null).window()).isNull();
	}

	@Test
	void identifiesANonPositiveWindowAsInvalidAndClearsTheDuration() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");

		ConnectionWindowCalculation same = windows.calculate(arrival, arrival);
		ConnectionWindowCalculation earlier = windows.calculate(arrival, arrival.minusMinutes(1));
		ConnectionWindowCalculation offsetLooksLaterButIsEarlier = windows.calculate(
				OffsetDateTime.parse("2026-10-15T08:00:00Z"),
				OffsetDateTime.parse("2026-10-15T10:00:00+08:00"));

		assertThat(same.status()).isEqualTo(Status.INVALID);
		assertThat(earlier.status()).isEqualTo(Status.INVALID);
		assertThat(offsetLooksLaterButIsEarlier.status()).isEqualTo(Status.INVALID);
		assertThat(same.window()).isNull();
		assertThat(earlier.window()).isNull();
		assertThat(offsetLooksLaterButIsEarlier.window()).isNull();
	}

	@Test
	void rejectsAContradictoryCalculation() {
		assertThatThrownBy(() -> new ConnectionWindowCalculation(Status.AVAILABLE, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ConnectionWindowCalculation(Status.INVALID,
				new ConnectionWindow("1 hour", 3600)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ConnectionWindow.of(Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ConnectionWindow.of(Duration.ofMinutes(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RemainingConnectionWindow.available(null, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RemainingConnectionWindow(RemainingConnectionWindow.Status.AVAILABLE,
				"1 hour", 3600L, "1 hour", 3600L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RemainingConnectionWindow(RemainingConnectionWindow.Status.MISSED,
				"1 hour", 3600L, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ConnectionWindow.durationText(Duration.ofMinutes(-1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void calculatesTheRemainingTimeFromTheEstimatedArrivalToTheFeederDeparture() {
		RemainingConnectionWindow remaining = windows.remaining(
				OffsetDateTime.parse("2026-10-16T12:00:00+08:00"),
				estimate("2026-10-15T08:00:00+08:00", false));

		assertThat(remaining.status()).isEqualTo(RemainingConnectionWindow.Status.AVAILABLE);
		assertThat(remaining.duration()).isEqualTo("1 day 4 hours");
		assertThat(remaining.totalSeconds()).isEqualTo(Duration.ofHours(28).toSeconds());
		assertThat(remaining.missedBy()).isNull();
		assertThat(remaining.missedBySeconds()).isNull();
	}

	@Test
	void comparesRemainingInstantsSoOffsetsAreNotLocalClockTimes() {
		RemainingConnectionWindow remaining = windows.remaining(
				OffsetDateTime.parse("2026-10-16T02:30:00Z"),
				estimate("2026-10-15T20:00:00+08:00", false));

		assertThat(remaining.duration()).isEqualTo("14 hours 30 minutes");
		assertThat(remaining.totalSeconds()).isEqualTo(Duration.ofHours(14).plusMinutes(30).toSeconds());
	}

	@Test
	void describesAShortRemainingWindowTheSameWayAsThePlannedWindow() {
		RemainingConnectionWindow oneSecond = windows.remaining(
				OffsetDateTime.parse("2026-10-15T08:00:01Z"),
				estimate("2026-10-15T08:00:00Z", false));
		RemainingConnectionWindow brief = windows.remaining(
				OffsetDateTime.parse("2026-10-15T08:00:00.500Z"),
				estimate("2026-10-15T08:00:00Z", false));

		assertThat(oneSecond.duration()).isEqualTo("1 second");
		assertThat(oneSecond.totalSeconds()).isEqualTo(1);
		assertThat(brief.status()).isEqualTo(RemainingConnectionWindow.Status.AVAILABLE);
		assertThat(brief.duration()).isEqualTo("less than 1 second");
		assertThat(brief.totalSeconds()).isZero();
	}

	@Test
	void recordsAMissedConnectionByHowLongTheEstimateOverrunsTheFeederDeparture() {
		RemainingConnectionWindow overrun = windows.remaining(
				OffsetDateTime.parse("2026-10-15T08:00:00Z"),
				estimate("2026-10-15T10:00:00Z", false));
		RemainingConnectionWindow brief = windows.remaining(
				OffsetDateTime.parse("2026-10-15T08:00:00Z"),
				estimate("2026-10-15T08:00:00.500Z", false));
		RemainingConnectionWindow equal = windows.remaining(
				OffsetDateTime.parse("2026-10-15T08:00:00+08:00"),
				estimate("2026-10-15T00:00:00Z", false));
		RemainingConnectionWindow offsetLooksLaterButIsEarlier = windows.remaining(
				OffsetDateTime.parse("2026-10-15T10:00:00+08:00"),
				estimate("2026-10-15T08:00:00Z", false));

		assertThat(overrun.status()).isEqualTo(RemainingConnectionWindow.Status.MISSED);
		assertThat(overrun.duration()).isNull();
		assertThat(overrun.totalSeconds()).isNull();
		assertThat(overrun.missedBy()).isEqualTo("2 hours");
		assertThat(overrun.missedBySeconds()).isEqualTo(Duration.ofHours(2).toSeconds());
		assertThat(brief.missedBy()).isEqualTo("less than 1 second");
		assertThat(brief.missedBySeconds()).isZero();
		assertThat(equal.missedBy()).isEqualTo("0 seconds");
		assertThat(equal.missedBySeconds()).isZero();
		assertThat(offsetLooksLaterButIsEarlier.missedBy()).isEqualTo("6 hours");
		assertThat(offsetLooksLaterButIsEarlier.missedBySeconds()).isEqualTo(Duration.ofHours(6).toSeconds());
	}

	@Test
	void leavesNoRemainingWindowWhenTheEstimateCannotBeUsed() {
		OffsetDateTime departure = OffsetDateTime.parse("2026-10-16T08:00:00Z");
		ArrivalEstimate usable = estimate("2026-10-15T08:00:00Z", false);

		assertThat(windows.remaining(departure, null)).isNull();
		assertThat(windows.remaining(null, usable)).isNull();
		assertThat(windows.remaining(departure, estimate("2026-10-15T08:00:00Z", true))).isNull();
		assertThat(windows.remaining(departure, ArrivalEstimate.unavailable("NO_POSITION",
				"No AIS position has been received for the mother vessel."))).isNull();
		assertThat(windows.remaining(departure, ArrivalEstimate.unavailable("INVALID_COORDINATES",
				"The vessel or port coordinates cannot be used."))).isNull();
		assertThat(windows.remaining(departure, ArrivalEstimate.unavailable("MISSING_PORT",
				"The transshipment port is missing."))).isNull();
		assertThat(windows.remaining(departure, ArrivalEstimate.unavailable("UNKNOWN_PORT",
				"The transshipment port is not on the route map, so its coordinates cannot be used."))).isNull();
		assertThat(windows.remaining(departure, ArrivalEstimate.unavailable("UNUSABLE_SPEED",
				"The mother vessel's speed is missing or below 0.5 knots."))).isNull();
		assertThat(windows.remaining(departure, usable)).isNotNull();
	}

	@Test
	void leavesTheEstimateReportTimeAsTheRemainingWindowsReportTime() {
		ArrivalEstimate estimate = estimate("2026-10-15T08:00:00Z", false);
		RemainingConnectionWindow remaining = windows.remaining(OffsetDateTime.parse("2026-10-16T08:00:00Z"), estimate);

		assertThat(remaining).isNotNull();
		assertThat(remaining.duration()).isEqualTo("1 day");
		assertThat(estimate.estimatedArrivalAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T08:00:00Z"));
		assertThat(estimate.lastReceivedAt()).isEqualTo(Instant.parse("2026-10-15T02:00:00Z"));
	}

	private static ArrivalEstimate estimate(String arrival, boolean possiblyOutdated) {
		return ArrivalEstimate.available(OffsetDateTime.parse(arrival), new BigDecimal("12.5"),
				Classification.ON_SCHEDULE, "On schedule · estimated at the planned time", 0, 0,
				Instant.parse("2026-10-15T02:00:00Z"), possiblyOutdated);
	}

	private String duration(String arrival, String departure) {
		return duration(OffsetDateTime.parse(arrival), OffsetDateTime.parse(departure));
	}

	private String duration(OffsetDateTime arrival, OffsetDateTime departure) {
		ConnectionWindowCalculation calculation = windows.calculate(arrival, departure);
		assertThat(calculation.status()).isEqualTo(Status.AVAILABLE);
		return calculation.window().duration();
	}
}
