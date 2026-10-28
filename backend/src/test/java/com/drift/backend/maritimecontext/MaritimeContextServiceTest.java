package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import com.drift.backend.shipment.ShipmentStatus;

class MaritimeContextServiceTest {
	@Test
	void disabledFeatureReturnsNoEvidence() {
		var response = new MaritimeContextService(false).forShipment(TestShipments.withImo("9351048"));
		assertThat(response.enabled()).isFalse();
		assertThat(response.notices()).isEmpty();
		assertThat(response.sourceMode()).isEqualTo("DISABLED");
	}

	@Test
	void enabledFixtureIsExplicitlySyntheticAndNeverClaimsCompatibility() {
		var response = new MaritimeContextService(true).forShipment(TestShipments.withImo("9351048"));
		assertThat(response.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION");
		assertThat(response.shipmentAssociationConfidence()).isEqualTo("UNVERIFIED_IMO_ONLY");
		assertThat(response.notices()).singleElement().satisfies(notice -> {
			assertThat(notice.compatibilityState()).isEqualTo("UNKNOWN");
			assertThat(notice.humanReviewRequired()).isTrue();
		});
	}

	private static final class TestShipments {
		private static com.drift.backend.shipment.ShipmentDetailResponse withImo(String imo) {
			return new com.drift.backend.shipment.ShipmentDetailResponse(
					1L, 1L, "DEMO", "SG", "ID", "SG", "KOTA LARIS", null,
					imo, null, null, null, null, null, null, null,
					Instant.EPOCH, 0L,
					null, null, null, null, null, null, null,
					null, null, null, null, null, null, ShipmentStatus.ACTIVE);
		}
	}
}
