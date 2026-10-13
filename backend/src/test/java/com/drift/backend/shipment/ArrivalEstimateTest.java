package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ArrivalEstimate.Classification;
import com.drift.backend.shipment.PortCatalog.Coordinates;

class ArrivalEstimateTest {

	private static final Instant RECEIVED = Instant.parse("2026-10-13T04:00:00Z");
	private static final BigDecimal LATITUDE = new BigDecimal("1.26");
	private static final BigDecimal LONGITUDE = new BigDecimal("103.82");
	private static final BigDecimal MOVING = new BigDecimal("12.4");

	private final ArrivalEstimateCalculator estimates = new ArrivalEstimateCalculator(PortCatalog.load());

	@Test
	void resolvesTheMapCatalogAndNotAnOperationalAlias() {
		PortCatalog ports = PortCatalog.load();

		assertThat(ports.locate("Port of Singapore")).contains(new Coordinates(103.82, 1.26));
		assertThat(ports.locate("Singapore, SG")).contains(new Coordinates(103.82, 1.26));
		assertThat(ports.locate("Tanjung Pelepas")).contains(new Coordinates(103.55, 1.36));
		assertThat(ports.locate("SGSIN")).isEmpty();
		assertThat(ports.locate("port of ")).isEmpty();
	}

	@Test
	void keepsAnEstimateAtLeastHalfAKnotAndAnchorsItToTheLastReceivedTime() {
		Clock early = clockAt(RECEIVED);
		Clock later = clockAt(RECEIVED.plus(Duration.ofHours(3)));

		ArrivalEstimate exact = estimates.estimate(fix(new BigDecimal("0.5")), "Singapore", planned(RECEIVED), early);
		ArrivalEstimate repeated = estimates.estimate(fix(new BigDecimal("0.5")), "Singapore", planned(RECEIVED), later);

		assertThat(exact.unavailableReason()).isNull();
		assertThat(exact.estimatedArrivalAt().toInstant()).isEqualTo(RECEIVED);
		assertThat(repeated.estimatedArrivalAt()).isEqualTo(exact.estimatedArrivalAt());
		assertThat(exact.lastReceivedAt()).isEqualTo(RECEIVED);
		assertThat(exact.limitation()).isEqualTo(ArrivalEstimate.LIMITATION);
		assertThat(exact.speedOverGroundKnots()).isEqualByComparingTo("0.5");
		assertThat(exact.classification()).isEqualTo(Classification.ON_SCHEDULE);
		assertThat(exact.difference()).isEqualTo("On schedule · estimated at the planned time");
		assertThat(exact.differenceSeconds()).isZero();
		assertThat(exact.differenceNanos()).isZero();
		assertThat(exact.possiblyOutdated()).isFalse();
	}

	@Test
	void rejectsSpeedBelowHalfAKnotAndANonFiniteSpeed() {
		assertUnavailable(estimates.estimate(fix(new BigDecimal("0.499999999")), "Singapore", planned(RECEIVED), clockAt(RECEIVED)),
				"UNUSABLE_SPEED");
		assertUnavailable(estimates.estimate(fix(null), "Singapore", planned(RECEIVED), clockAt(RECEIVED)),
				"UNUSABLE_SPEED");
		assertUnavailable(estimates.estimate(fix(new BigDecimal("-1")), "Singapore", planned(RECEIVED), clockAt(RECEIVED)),
				"UNUSABLE_SPEED");
		assertUnavailable(estimates.estimate(fix(new BigDecimal("1e1000")), "Singapore", planned(RECEIVED), clockAt(RECEIVED)),
				"UNUSABLE_SPEED");
	}

	@Test
	void classifiesTheUnroundedDurationAroundTwoHoursAndSixHours() {
		OffsetDateTime planned = planned(RECEIVED);
		ArrivalEstimate twoHours = estimates.estimate(fix(MOVING), "Singapore", planned.minusHours(2), clockAt(RECEIVED));
		ArrivalEstimate justOverTwoHours = estimates.estimate(fix(MOVING), "Singapore",
				planned.minusHours(2).minusNanos(1), clockAt(RECEIVED));
		ArrivalEstimate sixHoursOld = estimates.estimate(fix(MOVING), "Singapore", planned,
				clockAt(RECEIVED.plus(Duration.ofHours(6))));
		ArrivalEstimate justOverSixHours = estimates.estimate(fix(MOVING), "Singapore", planned,
				clockAt(RECEIVED.plus(Duration.ofHours(6)).plusNanos(1)));

		assertThat(twoHours.classification()).isEqualTo(Classification.ON_SCHEDULE);
		assertThat(twoHours.difference()).isEqualTo("On schedule · estimated 2h later than planned");
		assertThat(twoHours.differenceSeconds()).isEqualTo(Duration.ofHours(2).toSeconds());
		assertThat(twoHours.differenceNanos()).isZero();
		assertThat(twoHours.possiblyOutdated()).isFalse();

		assertThat(justOverTwoHours.classification()).isEqualTo(Classification.BEHIND_PLAN);
		assertThat(justOverTwoHours.difference()).isEqualTo("Behind plan · estimated just over 2h later than planned");
		assertThat(justOverTwoHours.difference()).isNotEqualTo("Behind plan · estimated 2h later than planned");
		assertThat(justOverTwoHours.differenceSeconds()).isEqualTo(Duration.ofHours(2).toSeconds());
		assertThat(justOverTwoHours.differenceNanos()).isEqualTo(1);
		assertThat(justOverTwoHours.estimatedArrivalAt()).isNotNull();

		assertThat(sixHoursOld.possiblyOutdated()).isFalse();
		assertThat(sixHoursOld.estimatedArrivalAt().toInstant()).isEqualTo(RECEIVED);
		assertThat(justOverSixHours.possiblyOutdated()).isTrue();
		assertThat(justOverSixHours.estimatedArrivalAt()).isEqualTo(sixHoursOld.estimatedArrivalAt());
	}

	@Test
	void describesAheadOnScheduleAndExactGapsWithoutErasingAFraction() {
		OffsetDateTime planned = planned(RECEIVED);

		ArrivalEstimate ahead = estimates.estimate(fix(MOVING), "Singapore", planned.plusMinutes(90), clockAt(RECEIVED));
		ArrivalEstimate onSchedule = estimates.estimate(fix(MOVING), "Singapore", planned.minusMinutes(90), clockAt(RECEIVED));
		ArrivalEstimate fraction = estimates.estimate(fix(MOVING), "Singapore", planned.minusSeconds(1).minusNanos(500_000_000),
				clockAt(RECEIVED));
		ArrivalEstimate finerFraction = estimates.estimate(fix(MOVING), "Singapore", planned.minusSeconds(1).minusNanos(250_000_000),
				clockAt(RECEIVED));
		ArrivalEstimate underOneSecond = estimates.estimate(fix(MOVING), "Singapore", planned.minusNanos(1), clockAt(RECEIVED));
		ArrivalEstimate underTwoHours = estimates.estimate(fix(MOVING), "Singapore",
				planned.minusHours(1).minusMinutes(59).minusSeconds(40), clockAt(RECEIVED));

		assertThat(ahead.classification()).isEqualTo(Classification.AHEAD_OF_PLAN);
		assertThat(ahead.difference()).isEqualTo("Ahead of plan · estimated 1h 30m earlier than planned");
		assertThat(ahead.differenceSeconds()).isEqualTo(-90 * 60L);
		assertThat(ahead.differenceNanos()).isZero();

		assertThat(onSchedule.classification()).isEqualTo(Classification.ON_SCHEDULE);
		assertThat(onSchedule.difference()).isEqualTo("On schedule · estimated 1h 30m later than planned");

		assertThat(fraction.difference()).isEqualTo("On schedule · estimated 1.5s later than planned");
		assertThat(finerFraction.difference()).isEqualTo("On schedule · estimated 1.25s later than planned");
		assertThat(fraction.difference()).isNotEqualTo(finerFraction.difference());

		assertThat(underOneSecond.classification()).isEqualTo(Classification.ON_SCHEDULE);
		assertThat(underOneSecond.difference()).isEqualTo("On schedule · estimated less than 1s later than planned");
		assertThat(underOneSecond.difference()).doesNotContain("0s").doesNotContain("0m");
		assertThat(underOneSecond.differenceSeconds()).isZero();
		assertThat(underOneSecond.differenceNanos()).isEqualTo(1);

		assertThat(underTwoHours.classification()).isEqualTo(Classification.ON_SCHEDULE);
		assertThat(underTwoHours.difference()).isEqualTo("On schedule · estimated 1h 59m 40s later than planned");
	}

	@Test
	void returnsAStructuredReasonAndNoArrivalTimeWhenAnInputCannotBeUsed() {
		Clock clock = clockAt(RECEIVED);
		OffsetDateTime planned = planned(RECEIVED);

		assertUnavailable(estimates.estimate(null, " ", planned, clock), "NO_POSITION");
		assertUnavailable(estimates.estimate(new ArrivalFix(new BigDecimal("91"), LONGITUDE, MOVING, RECEIVED),
				"Singapore", planned, clock), "INVALID_COORDINATES");
		assertUnavailable(estimates.estimate(new ArrivalFix(LATITUDE, null, MOVING, RECEIVED), "Singapore", planned, clock),
				"INVALID_COORDINATES");
		assertUnavailable(estimates.estimate(new ArrivalFix(new BigDecimal("1e1000"), LONGITUDE, MOVING, RECEIVED),
				"Singapore", planned, clock), "INVALID_COORDINATES");
		assertUnavailable(estimates.estimate(fix(MOVING), "   ", planned, clock), "MISSING_PORT");
		assertUnavailable(estimates.estimate(fix(MOVING), "SGSIN", planned, clock), "UNKNOWN_PORT");

		ArrivalEstimateCalculator brokenPort = new ArrivalEstimateCalculator(PortCatalog.of(
				Map.of("broken", new Coordinates(0, 120))));
		assertUnavailable(brokenPort.estimate(fix(MOVING), "broken", planned, clock), "UNKNOWN_PORT");
	}

	@Test
	void movesTheEstimateByGreatCircleTravelTimeWithoutUsingTheRequestClock() {
		ArrivalEstimateCalculator equator = new ArrivalEstimateCalculator(PortCatalog.of(
				Map.of("equator", new Coordinates(1, 0))));
		ArrivalFix fix = new ArrivalFix(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("60"), RECEIVED);
		Clock first = clockAt(RECEIVED);
		Clock second = clockAt(RECEIVED.plus(Duration.ofDays(2)));

		ArrivalEstimate estimate = equator.estimate(fix, "equator", planned(RECEIVED), first);
		ArrivalEstimate repeated = equator.estimate(fix, "equator", planned(RECEIVED), second);

		assertThat(estimate.estimatedArrivalAt()).isEqualTo(repeated.estimatedArrivalAt());
		assertThat(Duration.between(RECEIVED.plus(Duration.ofHours(1)), estimate.estimatedArrivalAt().toInstant()).abs())
				.isLessThan(Duration.ofSeconds(1));
		assertThat(estimate.estimatedArrivalAt().toInstant()).isNotEqualTo(second.instant());
		assertThat(estimate.classification()).isEqualTo(Classification.ON_SCHEDULE);
	}

	private static void assertUnavailable(ArrivalEstimate estimate, String code) {
		assertThat(estimate.unavailableReason().code()).isEqualTo(code);
		assertThat(estimate.unavailableReason().message()).isNotBlank();
		assertThat(estimate.estimatedArrivalAt()).isNull();
		assertThat(estimate.limitation()).isNull();
		assertThat(estimate.speedOverGroundKnots()).isNull();
		assertThat(estimate.classification()).isNull();
		assertThat(estimate.difference()).isNull();
		assertThat(estimate.differenceSeconds()).isNull();
		assertThat(estimate.differenceNanos()).isNull();
		assertThat(estimate.lastReceivedAt()).isNull();
		assertThat(estimate.possiblyOutdated()).isNull();
	}

	private static ArrivalFix fix(BigDecimal speed) {
		return new ArrivalFix(LATITUDE, LONGITUDE, speed, RECEIVED);
	}

	private static OffsetDateTime planned(Instant instant) {
		return instant.atOffset(ZoneOffset.ofHours(8));
	}

	private static Clock clockAt(Instant instant) {
		return Clock.fixed(instant, ZoneOffset.UTC);
	}
}
