package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

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
