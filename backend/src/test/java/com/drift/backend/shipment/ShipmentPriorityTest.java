package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ShipmentPriorityTest {

	private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
	private static final ConnectionWindowService WINDOWS = new ConnectionWindowService();

	@Test
	void aMissingFeederScheduleIsNotRanked() {
		assertThat(ShipmentPriority.of(ConnectionWindowCalculation.incomplete(), null, null, null, NOW)).isNull();
	}

	@Test
	void aSmallerBufferRanksHigherWithinTheSameRiskLevel() {
		ShipmentPriority twoHours = planned(Duration.ofHours(2), Duration.ofHours(30));
		ShipmentPriority elevenHours = planned(Duration.ofHours(11), Duration.ofHours(30));

		assertThat(twoHours.score()).isGreaterThan(elevenHours.score());
		assertThat(twoHours.severity()).isGreaterThan(elevenHours.severity());
	}

	@Test
	void severityKeepsTheRiskLevelOrderAtTheThresholds() {
		assertThat(planned(Duration.ofHours(11), Duration.ofDays(1)).severity()).isGreaterThan(0.75);
		assertThat(planned(Duration.ofHours(23), Duration.ofDays(1)).severity()).isGreaterThan(0.5);
		assertThat(planned(Duration.ofHours(48), Duration.ofDays(1)).severity()).isZero();
	}

	@Test
	void theSameBufferRanksHigherWhenTheFeederLeavesSooner() {
		ShipmentPriority soon = planned(Duration.ofHours(8), Duration.ofHours(6));
		ShipmentPriority later = planned(Duration.ofHours(8), Duration.ofDays(6));

		assertThat(soon.urgency()).isGreaterThan(later.urgency());
		assertThat(soon.score()).isGreaterThan(later.score());
		assertThat(soon.reasons()).contains("The feeder departs in 6 hours.");
	}

	@Test
	void aDepartedFeederHasNoUrgencyBecauseActingCannotChangeTheOutcome() {
		ShipmentPriority departed = planned(Duration.ofHours(8), Duration.ofHours(-1));

		assertThat(departed.urgency()).isZero();
		assertThat(departed.reasons())
				.contains("The feeder has already departed, so acting now cannot change this connection.");
		assertThat(departed.score()).isLessThan(planned(Duration.ofHours(8), Duration.ofHours(1)).score());
	}

	@Test
	void liveAisEvidenceOutranksThePlannedScheduleAlone() {
		OffsetDateTime feeder = NOW.plus(Duration.ofHours(10)).atOffset(ZoneOffset.UTC);
		ConnectionWindowCalculation planned = WINDOWS.calculate(feeder.minusHours(6), feeder);
		ArrivalEstimate live = estimate(feeder.minusHours(6), ArrivalEstimate.Classification.ON_SCHEDULE, false);

		ShipmentPriority withAis = ShipmentPriority.of(planned, WINDOWS.remaining(feeder, live), live, feeder, NOW);
		ShipmentPriority plannedOnly = ShipmentPriority.of(planned, null, null, feeder, NOW);

		assertThat(withAis.confidence()).isEqualTo(ShipmentPriority.LIVE_CONFIDENCE);
		assertThat(plannedOnly.confidence()).isEqualTo(ShipmentPriority.PLANNED_CONFIDENCE);
		assertThat(withAis.score()).isGreaterThan(plannedOnly.score());
		assertThat(withAis.reasons()).contains("Connection buffer remaining: 6 hours.",
				"Based on the mother vessel's live AIS position.");
	}

	@Test
	void anOutdatedAisPositionLowersConfidenceAndSaysSo() {
		OffsetDateTime feeder = NOW.plus(Duration.ofHours(10)).atOffset(ZoneOffset.UTC);
		ConnectionWindowCalculation planned = WINDOWS.calculate(feeder.minusHours(6), feeder);
		ArrivalEstimate outdated = estimate(feeder.minusHours(6), ArrivalEstimate.Classification.ON_SCHEDULE, true);

		ShipmentPriority priority = ShipmentPriority.of(planned, WINDOWS.remaining(feeder, outdated), outdated, feeder, NOW);

		assertThat(priority.confidence()).isEqualTo(ShipmentPriority.OUTDATED_CONFIDENCE);
		assertThat(priority.reasons())
				.contains("The latest AIS position is possibly outdated, so the planned schedule is used.");
	}

	@Test
	void anExpectedMissRanksAboveEveryOpenConnectionAndLongerMissesRankHigher() {
		OffsetDateTime feeder = NOW.plus(Duration.ofHours(10)).atOffset(ZoneOffset.UTC);
		ConnectionWindowCalculation planned = WINDOWS.calculate(feeder.minusHours(20), feeder);
		ArrivalEstimate shortMiss = estimate(feeder.plusHours(1), ArrivalEstimate.Classification.BEHIND_PLAN, false);
		ArrivalEstimate longMiss = estimate(feeder.plusHours(15), ArrivalEstimate.Classification.BEHIND_PLAN, false);

		ShipmentPriority missedByOne = ShipmentPriority.of(planned, WINDOWS.remaining(feeder, shortMiss), shortMiss, feeder, NOW);
		ShipmentPriority missedByFifteen = ShipmentPriority.of(planned, WINDOWS.remaining(feeder, longMiss), longMiss, feeder, NOW);
		ShipmentPriority tightButOpen = planned(Duration.ofMinutes(30), Duration.ofHours(10));

		assertThat(missedByOne.severity()).isEqualTo(1.0);
		assertThat(missedByFifteen.score()).isGreaterThan(missedByOne.score());
		assertThat(missedByOne.score()).isGreaterThan(tightButOpen.score());
		assertThat(missedByOne.reasons()).anyMatch(reason -> reason.startsWith("The connection is expected to be missed by"));
		assertThat(missedByOne.reasons()).anyMatch(reason -> reason.startsWith("The mother vessel is running behind"));
		assertThat(missedByFifteen.score()).isLessThanOrEqualTo(100);
	}

	@Test
	void reasonsComeOnlyFromTheInputs() {
		ShipmentPriority priority = planned(Duration.ofHours(30), Duration.ofDays(2));

		assertThat(priority.reasons()).containsExactly(
				"Planned connection buffer: 1 day 6 hours.",
				"The feeder departs in 2 days.",
				"Based on the planned schedule only; no usable AIS estimate is available.");
	}

	private static ShipmentPriority planned(Duration buffer, Duration untilFeederDeparture) {
		OffsetDateTime feeder = NOW.plus(untilFeederDeparture).atOffset(ZoneOffset.UTC);
		return ShipmentPriority.of(WINDOWS.calculate(feeder.minus(buffer), feeder), null, null, feeder, NOW);
	}

	private static ArrivalEstimate estimate(OffsetDateTime arrival, ArrivalEstimate.Classification classification,
			boolean possiblyOutdated) {
		return ArrivalEstimate.available(arrival, new BigDecimal("12.0"), classification, "2 hours", 7200, 0,
				NOW.minusSeconds(60), possiblyOutdated);
	}
}
