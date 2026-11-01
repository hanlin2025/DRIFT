package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.drift.backend.shipment.ShipmentStatus;

class MaritimeContextServiceTest {
	@Test
	void disabledFeatureReturnsNoEvidence() {
		var response = service(false).forShipment(TestShipments.withImo("9351048"));
		assertThat(response.enabled()).isFalse();
		assertThat(response.notices()).isEmpty();
		assertThat(response.sourceMode()).isEqualTo("DISABLED");
	}

	@Test
	void enabledBundleKeepsEverySourceModeAndNeverClaimsCompatibility() {
		var response = service(true).forShipment(TestShipments.withImo("9351048"));
		assertThat(response.contractVersion()).isEqualTo("cdg246.maritime-evidence-bundle.v1");
		assertThat(response.sourceMode()).isEqualTo("RECORDED_REPLAY");
		assertThat(response.shipmentAssociationConfidence()).isEqualTo("UNVERIFIED_IMO_ONLY");
		assertThat(response.evidence()).extracting(MaritimeEvidence::sourceType)
				.containsExactlyInAnyOrder("MPA_ETA_REVISION", "MPA_OFFICIAL_NOTICE", "NEA_WEATHER");
		assertThat(response.evidence()).filteredOn(item -> "MPA_OFFICIAL_NOTICE".equals(item.sourceType())).singleElement().satisfies(item -> {
			assertThat(item.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION");
			assertThat(item.compatibilityState()).isEqualTo("UNKNOWN");
			assertThat(item.humanReviewRequired()).isTrue();
		});
	}

	@Test
	void excludesEvidenceRetrievedAfterAssessmentCutoffButRetainsExplicitUnavailableSource() {
		var response = service(true).forShipment(TestShipments.withImo("9351048"), Instant.parse("2026-10-26T15:30:00Z"));
		assertThat(response.evidence()).extracting(MaritimeEvidence::sourceType).containsExactly("NEA_WEATHER");
		assertThat(response.evidence().getFirst().sourceMode()).isEqualTo("UNAVAILABLE");
		assertThat(response.sourceMode()).isEqualTo("UNAVAILABLE");
	}

	@Test
	void unknownVesselAssociationDoesNotBorrowTheRecordedReplay() {
		var response = service(true).forShipment(TestShipments.withImo("9387421"));
		assertThat(response.shipmentAssociationConfidence()).isEqualTo("UNAVAILABLE");
		assertThat(response.evidence()).extracting(MaritimeEvidence::sourceType).containsExactly("NEA_WEATHER");
		assertThat(response.sourceMode()).isEqualTo("UNAVAILABLE");
	}

	@Test
	void recordedEvidenceCannotPromoteItselfToVerifiedAssociation() {
		MaritimeEvidence recordedClaimingVerification = new MaritimeEvidence("MPA_ETA_REVISION", "RECORDED_REPLAY", "recorded", "Recorded", "MPA", "", "", null, null, null,
				"FRESH", "", "", "", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", true, true, "VERIFIED", "Recorded sources are not a shipment booking.");
		MaritimeContextResponse response = new MaritimeContextService(true, List.of((shipment, cutoff) -> List.of(recordedClaimingVerification)), Clock.systemUTC())
				.forShipment(TestShipments.withImo("9351048"));
		assertThat(response.shipmentAssociationConfidence()).isEqualTo("UNAVAILABLE");
	}

	private static MaritimeContextService service(boolean enabled) {
		return new MaritimeContextService(enabled,
				List.of(new RecordedMpaRevisionEvidenceAdapter(), new SyntheticNoticeEvidenceAdapter(), new UnavailableNeaWeatherEvidenceAdapter()),
				Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC));
	}

	private static final class TestShipments {
		private static com.drift.backend.shipment.ShipmentDetailResponse withImo(String imo) {
			return new com.drift.backend.shipment.ShipmentDetailResponse(
					1L, 1L, "DEMO", "SG", "ID", "SG", "KOTA LARIS", null,
					imo, null, null, null, null, null, null, null,
					Instant.EPOCH, 0L,
					null, null, null, null, null, null, null,
					null, null, null, null, null, null, null, null, ShipmentStatus.ACTIVE);
		}
	}
}
