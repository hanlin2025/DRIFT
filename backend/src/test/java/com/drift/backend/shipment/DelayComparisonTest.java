package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ArrivalEstimate.Classification;
import com.drift.backend.shipment.DelayComparison.Outcome;
import com.drift.backend.shipment.DelayComparison.Reason;

class DelayComparisonTest {

	private static final OffsetDateTime DEADLINE = OffsetDateTime.parse("2026-10-28T16:00:00.000000001Z");
	private static final Instant RECEIVED = Instant.parse("2026-10-28T10:00:00Z");

	@Test
	void comparesBeforeEqualAndAfterAtNanosecondPrecision() {
		assertThat(compare(DEADLINE.minusNanos(1), false).outcome()).isEqualTo(Outcome.NO_PREDICTED_IMPACT);
		assertThat(compare(DEADLINE, false).outcome()).isEqualTo(Outcome.NO_PREDICTED_IMPACT);
		DelayComparison after = compare(DEADLINE.plusNanos(1), false);

		assertThat(after.outcome()).isEqualTo(Outcome.EXCEEDS_DEADLINE);
		assertThat(after.reason()).isNull();
		assertThat(after.message()).isEqualTo(DelayComparison.EXCEEDS);
		assertThat(after.estimatedArrivalAt()).isEqualTo(DEADLINE.plusNanos(1));
		assertThat(after.deadline()).isEqualTo(DEADLINE);
		assertThat(after.recordedTransshipmentPort()).isEqualTo("Singapore");
	}

	@Test
	void treatsEquivalentOffsetsAsTheSameInstant() {
		DelayComparison comparison = DelayComparison.of(
				available(OffsetDateTime.parse("2026-10-29T00:00:00+08:00"), false),
				deadline(OffsetDateTime.parse("2026-10-28T16:00:00Z"), "Singapore", true));

		assertThat(comparison.outcome()).isEqualTo(Outcome.NO_PREDICTED_IMPACT);
		assertThat(comparison.reason()).isNull();
		assertThat(comparison.message()).isEqualTo(DelayComparison.NO_IMPACT);
		assertThat(comparison.estimatedArrivalAt().toInstant()).isEqualTo(comparison.deadline().toInstant());
	}

	@Test
	void keepsTheExistingOnScheduleClassificationWhenTheDeadlineIsExceeded() {
		OffsetDateTime planned = OffsetDateTime.parse("2026-10-28T16:00:00Z");
		OffsetDateTime estimate = planned.plusMinutes(90);
		ArrivalEstimate onSchedule = ArrivalEstimate.available(estimate, new BigDecimal("10"), Classification.ON_SCHEDULE,
				"On schedule · estimated 1h 30m later than planned", 5_400, 0, RECEIVED, false);

		DelayComparison comparison = DelayComparison.of(onSchedule, deadline(planned.plusMinutes(30), "Singapore", true));

		assertThat(onSchedule.classification()).isEqualTo(Classification.ON_SCHEDULE);
		assertThat(comparison.outcome()).isEqualTo(Outcome.EXCEEDS_DEADLINE);
		assertThat(comparison.estimatedArrivalAt()).isEqualTo(estimate);
	}

	@Test
	void usesTheExistingFreshnessFlagWithoutRecalculatingIt() {
		DelayComparison fresh = compare(DEADLINE.minusNanos(1), false);
		DelayComparison stale = compare(DEADLINE.minusNanos(1), true);

		assertThat(fresh.outcome()).isEqualTo(Outcome.NO_PREDICTED_IMPACT);
		assertThat(fresh.reason()).isNull();
		assertThat(stale.outcome()).isNull();
		assertThat(stale.reason()).isEqualTo(Reason.STALE_PREDICTION);
		assertThat(stale.message()).isEqualTo(DelayComparison.STALE);
		assertThat(stale.estimatedArrivalAt()).isEqualTo(DEADLINE.minusNanos(1));
		assertThat(stale.deadline()).isEqualTo(DEADLINE);
	}

	@Test
	void returnsOneReasonInTheAgreedOrder() {
		TransshipmentArrivalDeadlineResponse incompatible = deadline(DEADLINE, "Tanjung Pelepas", false);
		TransshipmentArrivalDeadlineResponse applicable = deadline(DEADLINE, "Singapore", true);
		ArrivalEstimate missing = ArrivalEstimate.unavailable("NO_POSITION",
				"No AIS position has been received for the mother vessel.");
		ArrivalEstimate stale = available(DEADLINE.minusHours(1), true);
		ArrivalEstimate fresh = available(DEADLINE.minusHours(1), false);

		DelayComparison portAndMissing = DelayComparison.of(missing, incompatible);
		assertThat(portAndMissing.reason()).isEqualTo(Reason.INCOMPATIBLE_MILESTONE);
		assertThat(portAndMissing.outcome()).isNull();
		assertThat(portAndMissing.estimatedArrivalAt()).isNull();
		assertThat(portAndMissing.recordedTransshipmentPort()).isEqualTo("Tanjung Pelepas");
		assertThat(portAndMissing.message()).isEqualTo(DelayComparison.INCOMPATIBLE);

		DelayComparison portAndFresh = DelayComparison.of(fresh, incompatible);
		assertThat(portAndFresh.reason()).isEqualTo(Reason.INCOMPATIBLE_MILESTONE);
		assertThat(portAndFresh.estimatedArrivalAt()).isEqualTo(fresh.estimatedArrivalAt());

		DelayComparison missingAndNoDeadline = DelayComparison.of(missing, null);
		assertThat(missingAndNoDeadline.reason()).isEqualTo(Reason.MISSING_PREDICTION);
		assertThat(missingAndNoDeadline.deadline()).isNull();
		assertThat(missingAndNoDeadline.message()).isEqualTo(DelayComparison.MISSING_PREDICTION_MESSAGE);

		DelayComparison missingWithDeadline = DelayComparison.of(missing, applicable);
		assertThat(missingWithDeadline.reason()).isEqualTo(Reason.MISSING_PREDICTION);
		assertThat(missingWithDeadline.deadline()).isEqualTo(DEADLINE);

		DelayComparison noDeadlineButStale = DelayComparison.of(stale, null);
		assertThat(noDeadlineButStale.reason()).isEqualTo(Reason.MISSING_CONSTRAINT);
		assertThat(noDeadlineButStale.estimatedArrivalAt()).isEqualTo(stale.estimatedArrivalAt());
		assertThat(noDeadlineButStale.recordedTransshipmentPort()).isNull();
		assertThat(noDeadlineButStale.message()).isEqualTo(DelayComparison.MISSING_CONSTRAINT_MESSAGE);

		assertThat(DelayComparison.of(stale, applicable).reason()).isEqualTo(Reason.STALE_PREDICTION);
		assertThat(DelayComparison.of(fresh, applicable).outcome()).isEqualTo(Outcome.NO_PREDICTED_IMPACT);
	}

	private static DelayComparison compare(OffsetDateTime estimate, boolean possiblyOutdated) {
		return DelayComparison.of(available(estimate, possiblyOutdated), deadline(DEADLINE, "Singapore", true));
	}

	private static ArrivalEstimate available(OffsetDateTime estimate, boolean possiblyOutdated) {
		return ArrivalEstimate.available(estimate.withOffsetSameInstant(ZoneOffset.UTC), new BigDecimal("10"),
				Classification.AHEAD_OF_PLAN, "Ahead of plan", -60, 0, RECEIVED, possiblyOutdated);
	}

	private static TransshipmentArrivalDeadlineResponse deadline(OffsetDateTime deadline, String port, boolean applicable) {
		return new TransshipmentArrivalDeadlineResponse(deadline, port, applicable);
	}
}
