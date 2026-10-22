package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.exception.InvalidShipmentRequestException;

class ShipmentSortTest {

	private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-10-20T00:00:00Z");

	@Test
	void blankKeepsNewestFirstAndRiskOrPriorityAreReadCaseInsensitively() {
		assertThat(ShipmentSort.fromListParameter(null)).isEqualTo(ShipmentSort.CREATED_DATE_DESCENDING);
		assertThat(ShipmentSort.fromListParameter("  ")).isEqualTo(ShipmentSort.CREATED_DATE_DESCENDING);
		assertThat(ShipmentSort.fromListParameter(" Risk ")).isEqualTo(ShipmentSort.RISK_FIRST);
		assertThat(ShipmentSort.fromListParameter("PRIORITY")).isEqualTo(ShipmentSort.PRIORITY_FIRST);
	}

	@Test
	void rejectsUnknownSortValuesWithAStructuredError() {
		assertThatThrownBy(() -> ShipmentSort.fromListParameter("newest"))
				.isInstanceOfSatisfying(InvalidShipmentRequestException.class,
						e -> assertThat(e.getErrors()).containsEntry("sort", "Sort must be risk or priority"));
	}

	@Test
	void calculatedSortsAreNeverSentToTheDatabaseAndOnlyPutTheMostUrgentFirst() {
		assertThat(ShipmentSort.RISK_FIRST.computed()).isTrue();
		assertThat(ShipmentSort.CREATED_DATE_DESCENDING.computed()).isFalse();
		assertThatThrownBy(ShipmentSort.PRIORITY_FIRST::toSort).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new ShipmentSort(ShipmentSort.Field.RISK, ShipmentSort.Direction.ASC))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void riskOrderIsCriticalToLowThenNotAssessedWithTheSoonestFeederDepartureBreakingTies() {
		List<ShipmentResponse> rows = List.of(
				row(1, null, null, DEPARTURE),
				row(2, ShipmentRisk.Level.LOW, 10, DEPARTURE),
				row(3, ShipmentRisk.Level.HIGH, 60, DEPARTURE.plusHours(5)),
				row(4, ShipmentRisk.Level.CRITICAL, 50, DEPARTURE.plusDays(3)),
				row(5, ShipmentRisk.Level.HIGH, 40, DEPARTURE.plusHours(1)),
				row(6, ShipmentRisk.Level.MEDIUM, 30, DEPARTURE));

		assertThat(rows.stream().sorted(ShipmentSort.RISK_FIRST.comparator()).map(ShipmentResponse::id))
				.containsExactly(4L, 5L, 3L, 6L, 2L, 1L);
	}

	@Test
	void priorityOrderFollowsTheScoreThenRiskLevelAndKeepsNotAssessedLast() {
		List<ShipmentResponse> rows = List.of(
				row(1, null, null, DEPARTURE),
				row(2, ShipmentRisk.Level.CRITICAL, 35, DEPARTURE.minusHours(2)),
				row(3, ShipmentRisk.Level.HIGH, 70, DEPARTURE),
				row(4, ShipmentRisk.Level.MEDIUM, 35, DEPARTURE),
				row(5, ShipmentRisk.Level.LOW, 0, DEPARTURE));

		assertThat(rows.stream().sorted(ShipmentSort.PRIORITY_FIRST.comparator()).map(ShipmentResponse::id))
				.containsExactly(3L, 2L, 4L, 5L, 1L);
	}

	private static ShipmentResponse row(long id, ShipmentRisk.Level level, Integer score, OffsetDateTime feederDeparture) {
		ShipmentRisk risk = level == null ? null : new ShipmentRisk(level, level.name());
		ShipmentPriority priority = score == null ? null : new ShipmentPriority(score, 0, 0, 1, List.of());
		return new ShipmentResponse(id, 1L, "HBL-" + id, "Jakarta", "Rotterdam", "Singapore", "MV Mother",
				feederDeparture.minusHours(8), "MV Feeder", feederDeparture, null, 0L, null, risk, priority, null,
				ShipmentStatus.ACTIVE);
	}
}
