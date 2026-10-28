package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;

class SyntheticMaritimeAiSmokeFixtureTest {
	@Test void producesOnlyIndependentSyntheticEvidenceWithoutConsultingShipmentIdentity() {
		MaritimeContextResponse response = SyntheticMaritimeAiSmokeFixture.context().forShipment(shipment("real-looking-but-unused-imo"));

		assertThat(response.enabled()).isTrue();
		assertThat(response.evidence()).singleElement().satisfies(item -> {
			assertThat(item.sourceMode()).isEqualTo("SYNTHETIC_SMOKE_TEST");
			assertThat(item.evidenceId()).isEqualTo("synthetic-smoke-001");
			assertThat(item.evidenceSufficient()).isTrue();
			assertThat(item.title()).doesNotContainIgnoringCase("KOTA");
			assertThat(item.citation()).doesNotContainIgnoringCase("MPA");
			assertThat(item.shipmentAssociationConfidence()).isEqualTo("SYNTHETIC_TEST_FIXTURE");
		});
	}

	private static ShipmentDetailResponse shipment(String imo) {
		return new ShipmentDetailResponse(
				99L, 99L, "SYNTHETIC-SMOKE", "Synthetic origin", "Synthetic destination", "Synthetic port", "Synthetic vessel", null,
				imo, null, null, null, null, null, null, null,
				Instant.EPOCH, 0L,
				null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, ShipmentStatus.ACTIVE);
	}
}
