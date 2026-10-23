package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ShipmentRisk.Level;

class ShipmentRiskTest {

	private static final String PLANNED = " The planned connection window is being used.";

	private final ConnectionWindowService windows = new ConnectionWindowService();

	@Test
	void ratesThePlannedWindowAtTheTwelveAndTwentyFourHourBoundaries() {
		assertThat(planned("2026-10-15T08:00:00Z", "2026-10-15T19:59:59Z").level()).isEqualTo(Level.HIGH);
		assertThat(planned("2026-10-15T08:00:00Z", "2026-10-15T20:00:00Z").level()).isEqualTo(Level.MEDIUM);
		assertThat(planned("2026-10-15T08:00:00Z", "2026-10-16T07:59:59Z").level()).isEqualTo(Level.MEDIUM);
		assertThat(planned("2026-10-15T08:00:00Z", "2026-10-16T08:00:00Z").level()).isEqualTo(Level.LOW);
	}

	@Test
	void namesThePlannedWindowWhenTheEstimateCannotBeUsed() {
		assertThat(planned("2026-10-15T08:00:00Z", "2026-10-15T10:00:00Z").explanation())
				.isEqualTo("Less than 12 hours to transfer cargo between vessels." + PLANNED);
		assertThat(planned("2026-10-15T08:00:00Z", "2026-10-16T02:00:00Z").explanation())
				.isEqualTo("Less than 24 hours to transfer cargo between vessels." + PLANNED);
		assertThat(planned("2026-10-15T08:00:00Z", "2026-10-17T08:00:00Z").explanation())
				.isEqualTo("At least 24 hours to transfer cargo between vessels." + PLANNED);
	}

	@Test
	void marksAFeederDepartingAtOrBeforeTheMotherArrivalAsCritical() {
		ShipmentRisk equal = planned("2026-10-15T08:00:00Z", "2026-10-15T08:00:00Z");
		ShipmentRisk earlier = planned("2026-10-15T08:00:00Z", "2026-10-15T07:00:00Z");

		assertThat(equal).isEqualTo(earlier);
		assertThat(equal.level()).isEqualTo(Level.CRITICAL);
		assertThat(equal.explanation()).isEqualTo(
				"The feeder vessel is planned to depart at or before the mother vessel's arrival, leaving no time to transfer cargo."
						+ PLANNED);
	}

	@Test
	void leavesTheRiskUnassessedUntilTheFeederIsScheduled() {
		assertThat(ShipmentRisk.of(windows.calculate(OffsetDateTime.parse("2026-10-15T08:00:00Z"), null), null)).isNull();
		assertThat(windows.remaining(null, null)).isNull();
	}

	@Test
	void ratesTheRemainingWindowAtTheSameBoundaries() {
		assertThat(level(Duration.ofHours(12).minusSeconds(1))).isEqualTo(Level.HIGH);
		assertThat(level(Duration.ofHours(12))).isEqualTo(Level.MEDIUM);
		assertThat(level(Duration.ofHours(24).minusSeconds(1))).isEqualTo(Level.MEDIUM);
		assertThat(level(Duration.ofHours(24))).isEqualTo(Level.LOW);
	}

	@Test
	void namesTheRemainingWindowWhenTheEstimateCanBeUsed() {
		assertThat(explanation(Duration.ofHours(2)))
				.isEqualTo("Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
		assertThat(explanation(Duration.ofHours(18)))
				.isEqualTo("Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
		assertThat(explanation(Duration.ofHours(30)))
				.isEqualTo("At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
	}

	@Test
	void prefersTheRemainingWindowOverADifferentPlannedWindow() {
		ConnectionWindowCalculation planned = windows.calculate(
				OffsetDateTime.parse("2026-10-15T08:00:00Z"),
				OffsetDateTime.parse("2026-10-17T08:00:00Z"));
		RemainingConnectionWindow remaining = RemainingConnectionWindow.available("2 hours", Duration.ofHours(2).toSeconds());

		ShipmentRisk risk = ShipmentRisk.of(planned, remaining);

		assertThat(planned.window().totalSeconds()).isEqualTo(Duration.ofHours(48).toSeconds());
		assertThat(risk.level()).isEqualTo(Level.HIGH);
		assertThat(risk.explanation())
				.isEqualTo("Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");
	}

	@Test
	void marksAMissedRemainingWindowAsCritical() {
		ShipmentRisk overrun = ShipmentRisk.of(longPlan(), RemainingConnectionWindow.missed("2 hours", 7_200));
		ShipmentRisk brief = ShipmentRisk.of(longPlan(), RemainingConnectionWindow.missed("less than 1 second", 0));
		ShipmentRisk equal = ShipmentRisk.of(longPlan(), RemainingConnectionWindow.missed("0 seconds", 0));

		assertThat(overrun.level()).isEqualTo(Level.CRITICAL);
		assertThat(overrun.explanation()).isEqualTo("The connection is expected to be missed by 2 hours.");
		assertThat(brief.explanation()).isEqualTo("The connection is expected to be missed by less than 1 second.");
		assertThat(equal.explanation()).isEqualTo(
				"The connection is expected to be missed. The mother vessel's estimated arrival is at the planned feeder departure.");
	}

	@Test
	void requiresThePlannedCalculation() {
		assertThatThrownBy(() -> ShipmentRisk.of(null, null)).isInstanceOf(IllegalArgumentException.class);
	}

	private ShipmentRisk planned(String motherArrival, String feederDeparture) {
		return ShipmentRisk.of(windows.calculate(OffsetDateTime.parse(motherArrival), OffsetDateTime.parse(feederDeparture)),
				null);
	}

	private ShipmentRisk levelRisk(Duration remaining) {
		return ShipmentRisk.of(longPlan(), RemainingConnectionWindow.available(remaining.toString(), remaining.toSeconds()));
	}

	private Level level(Duration remaining) {
		return levelRisk(remaining).level();
	}

	private String explanation(Duration remaining) {
		return levelRisk(remaining).explanation();
	}

	private ConnectionWindowCalculation longPlan() {
		return windows.calculate(OffsetDateTime.parse("2026-10-15T08:00:00Z"), OffsetDateTime.parse("2026-10-17T08:00:00Z"));
	}
}
