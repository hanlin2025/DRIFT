package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;

class SyntheticMaritimeAiSmokeFixtureTest {
	@Test void producesVerifiedEvidenceOnlyForTheFullyMatchedFictionalShipment() {
		MaritimeContextResponse response = SyntheticMaritimeAiSmokeFixture.context().forShipment(shipment("9000001"));

		assertThat(response.enabled()).isTrue();
		assertThat(response.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
		assertThat(response.evidence()).singleElement().satisfies(item -> {
			assertThat(item.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION");
			assertThat(item.evidenceId()).isEqualTo("synthetic-smoke-001");
			assertThat(item.evidenceSufficient()).isTrue();
			assertThat(item.title()).doesNotContainIgnoringCase("KOTA");
			assertThat(item.citation()).doesNotContainIgnoringCase("MPA");
			assertThat(item.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
		});
		assertThat(SyntheticMaritimeAiSmokeFixture.context().forShipment(shipment("real-looking-but-unused-imo")).evidence()).isEmpty();
	}

	private static ShipmentDetailResponse shipment(String imo) {
		return new ShipmentDetailResponse(
				99L, 99L, "SYNTHETIC-SMOKE", "Synthetic origin", "Synthetic destination", "Synthetic port", "Synthetic vessel",
				OffsetDateTime.parse("2026-10-01T08:00:00+08:00"), imo, null, null, "Synthetic feeder", OffsetDateTime.parse("2026-10-01T16:00:00+08:00"), null, null, null,
				Instant.EPOCH, 0L,
				null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, ShipmentStatus.ACTIVE);
	}
}
