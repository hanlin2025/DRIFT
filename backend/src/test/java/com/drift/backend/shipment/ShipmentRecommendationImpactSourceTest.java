package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class ShipmentRecommendationImpactSourceTest {

	private final ShipmentRecommendationImpactSource impacts = new ShipmentRecommendationImpactSource();

	@Test
	void aCompletedComparisonBecomesTheRecommendationImpact() {
		assertThat(impacts.fromComparison(DelayComparison.of(fresh("2026-10-28T16:00:00Z"), deadline("2026-10-28T16:00:00Z", true))))
				.isEqualTo(ShipmentRecommendationImpact.noPredictedImpact());
		assertThat(impacts.fromComparison(DelayComparison.of(fresh("2026-10-28T16:00:00.000001Z"), deadline("2026-10-28T16:00:00Z", true))))
				.isEqualTo(ShipmentRecommendationImpact.affectsConstraints());
	}

	@Test
	void aMissingStaleOrIncompatibleComparisonStaysUnavailable() {
		assertThatThrownBy(() -> impacts.fromComparison(null)).isInstanceOf(IllegalArgumentException.class);
		assertThat(impacts.fromComparison(DelayComparison.of(null, null)).detail())
				.isEqualTo(DelayComparison.MISSING_PREDICTION_MESSAGE);
		assertThat(impacts.fromComparison(DelayComparison.of(fresh("2026-10-28T16:00:00Z"), null)).detail())
				.isEqualTo(DelayComparison.MISSING_CONSTRAINT_MESSAGE);
		assertThat(impacts.fromComparison(DelayComparison.of(stale("2026-10-28T16:00:00Z"), deadline("2026-10-28T18:00:00Z", true))).detail())
				.isEqualTo(DelayComparison.STALE);
		assertThat(impacts.fromComparison(DelayComparison.of(fresh("2026-10-28T16:00:00Z"), deadline("2026-10-28T18:00:00Z", false))).detail())
				.isEqualTo(DelayComparison.INCOMPATIBLE);
	}

	private static ArrivalEstimate fresh(String arrival) {
		return estimate(arrival, false);
	}

	private static ArrivalEstimate stale(String arrival) {
		return estimate(arrival, true);
	}

	private static ArrivalEstimate estimate(String arrival, boolean possiblyOutdated) {
		return new ArrivalEstimate(OffsetDateTime.parse(arrival), ArrivalEstimate.LIMITATION, new BigDecimal("12.5"),
				ArrivalEstimate.Classification.ON_SCHEDULE, "on schedule", 0L, 0, Instant.parse("2026-10-28T10:00:00Z"),
				possiblyOutdated, null);
	}

	private static TransshipmentArrivalDeadlineResponse deadline(String deadline, boolean applicable) {
		return new TransshipmentArrivalDeadlineResponse(OffsetDateTime.parse(deadline), "Singapore", applicable);
	}
}
