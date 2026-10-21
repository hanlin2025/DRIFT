package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.exception.InvalidShipmentRequestException;

class ShipmentFilterCriteriaTest {

	@Test
	void defaultsToCreatedAtOrderAndNoRiskFilter() {
		ShipmentFilterCriteria none = ShipmentFilterCriteria.none();

		assertThat(none.sort()).isEqualTo(ShipmentFilterCriteria.Sort.CREATED_AT);
		assertThat(none.atRisk()).isFalse();
		assertThat(ShipmentFilterCriteria.of(null, null, null, null, null, " ", null).sort())
				.isEqualTo(ShipmentFilterCriteria.Sort.CREATED_AT);
	}

	@Test
	void parsesRiskSortCaseInsensitivelyAndAtRiskOnlyWhenTrue() {
		assertThat(ShipmentFilterCriteria.of(null, null, null, null, null, " Risk ", true))
				.satisfies(criteria -> {
					assertThat(criteria.sort()).isEqualTo(ShipmentFilterCriteria.Sort.RISK);
					assertThat(criteria.atRisk()).isTrue();
				});
		assertThat(ShipmentFilterCriteria.of(null, null, null, null, null, null, false).atRisk()).isFalse();
	}

	@Test
	void rejectsUnknownSortValuesWithAStructuredError() {
		assertThatThrownBy(() -> ShipmentFilterCriteria.of(null, null, null, null, null, "newest", null))
				.isInstanceOfSatisfying(InvalidShipmentRequestException.class,
						e -> assertThat(e.getErrors()).containsKey("sort"));
	}
}
