package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;

class SyntheticMultiSourceMaritimeFixtureTest {
	@Test
	void assemblesThreeIndependentSyntheticSourcesThroughTheNormalContextService() {
		MaritimeContextResponse response = SyntheticMultiSourceMaritimeFixture.context().forShipment(fictionalShipment());

		assertThat(response.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION");
		assertThat(response.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
		assertThat(response.evidence()).extracting(MaritimeEvidence::evidenceId).containsExactlyInAnyOrder(
				SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID,
				SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID,
				SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID,
				SyntheticMultiSourceMaritimeFixture.INCOMPATIBLE_NOTICE_EVIDENCE_ID);
		assertThat(response.evidence()).allSatisfy(item -> {
			assertThat(item.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION");
			assertThat(item.retrievedAtUtc()).isNotBlank();
			assertThat(item.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
			assertThat(item.limitation()).contains("Fictional");
		});
		assertThat(response.evidence()).filteredOn(item -> item.evidenceId().equals(SyntheticMultiSourceMaritimeFixture.INCOMPATIBLE_NOTICE_EVIDENCE_ID))
				.singleElement().satisfies(item -> assertThat(item.compatibilityState()).isEqualTo("INCOMPATIBLE"));
		assertThat(response.evidence()).filteredOn(item -> item.evidenceId().equals(SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID))
				.singleElement().satisfies(item -> {
					assertThat(item.vesselIdentity()).isEqualTo("Synthetic Multi-source Vessel");
					assertThat(item.vesselRole()).isEqualTo("MOTHER");
					assertThat(item.observationType()).isEqualTo("EXPECTED_ARRIVAL");
					assertThat(item.associationScope()).isEqualTo("VESSEL_OBSERVATION");
				});
		assertThat(response.evidence()).filteredOn(item -> item.evidenceId().equals(SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID))
				.singleElement().satisfies(item -> assertThat(item.associationScope()).isEqualTo("PORT_CONTEXT"));
		assertThat(response.evidence()).filteredOn(item -> item.evidenceId().equals(SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID))
				.singleElement().satisfies(item -> assertThat(item.associationScope()).isEqualTo("REGIONAL_CONTEXT"));
		assertThat(response.notices()).filteredOn(notice -> notice.noticeId().equals(SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID)).singleElement().satisfies(notice -> {
			assertThat(notice.noticeId()).isEqualTo(SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID);
			assertThat(notice.citation()).contains("page 1");
		});
	}

	@Test
	void excludesEverySourceUntilItsOwnRetrievalTimeAndNeverBorrowsAnotherShipmentEvidence() {
		MaritimeContextService context = SyntheticMultiSourceMaritimeFixture.context();
		assertThat(context.forShipment(fictionalShipment(), Instant.parse("2026-09-30T21:15:00Z")).evidence())
			.extracting(MaritimeEvidence::evidenceId).containsExactly(SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID);
		assertThat(context.forShipment(fictionalShipment(), Instant.parse("2026-09-30T21:45:00Z")).evidence())
			.extracting(MaritimeEvidence::evidenceId).containsExactlyInAnyOrder(
					SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID, SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID);
		assertThat(context.forShipment(unrelatedShipment(), Instant.parse("2026-10-01T00:00:00Z")).evidence()).isEmpty();
	}

	@Test
	void replaysBaselineArrivalRevisionAndContextStagesDeterministically() {
		assertThat(SyntheticMultiSourceMaritimeFixture.contextForStage(SyntheticMultiSourceMaritimeFixture.ReplayStage.BASELINE)
				.forShipment(fictionalShipment()).evidence()).isEmpty();
		assertThat(SyntheticMultiSourceMaritimeFixture.contextForStage(SyntheticMultiSourceMaritimeFixture.ReplayStage.ARRIVAL_REVISION)
				.forShipment(fictionalShipment()).evidence()).extracting(MaritimeEvidence::evidenceId)
				.containsExactly(SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID);
		assertThat(SyntheticMultiSourceMaritimeFixture.contextForStage(SyntheticMultiSourceMaritimeFixture.ReplayStage.MARITIME_CONTEXT)
				.forShipment(fictionalShipment()).evidence()).extracting(MaritimeEvidence::evidenceId)
				.containsExactlyInAnyOrder(SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID,
						SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID,
						SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID,
						SyntheticMultiSourceMaritimeFixture.INCOMPATIBLE_NOTICE_EVIDENCE_ID);
	}

	@Test
	void controlledReplayKeepsExpectedArrivalValuesSeparateFromUnknownObservationMetadata() {
		MaritimeContextService context = new MaritimeContextService(true,
				List.of(new ControlledScenarioReplayEvidenceAdapter(true, "MARITIME_CONTEXT")), Clock.systemUTC());
		MaritimeEvidence mpa = context.forShipment(controlledReplayShipment()).evidence().stream()
				.filter(item -> item.evidenceId().equals("demo-synthetic-mpa-schedule-001")).findFirst().orElseThrow();
		MaritimeEvidence weather = context.forShipment(controlledReplayShipment()).evidence().stream()
				.filter(item -> item.evidenceId().equals("demo-synthetic-nea-rainfall-001")).findFirst().orElseThrow();

		assertThat(mpa.previousValue()).isEqualTo("2026-10-01T05:00:00+08:00");
		assertThat(mpa.currentValue()).isEqualTo("2026-10-01T06:00:00+08:00");
		assertThat(mpa.sourceEventAt()).isNull();
		assertThat(mpa.observedAt()).isNull();
		assertThat(mpa.retrievedAtUtc()).isEqualTo("2026-09-30T21:00:00Z");
		assertThat(weather.observedAt()).isEqualTo("2026-10-01T05:00:00+08:00");
	}

	@Test
	void controlledReplaySuppressesTheSeparateLiveNeaUnavailablePlaceholder() {
		assertThat(new UnavailableNeaWeatherEvidenceAdapter(false, true).forShipment(fictionalShipment(), Instant.now())).isEmpty();
	}

	@Test
	void supportsDeterministicMpaOnlyNeaOnlyAndNoSourceAvailabilityCases() {
		assertThat(SyntheticMultiSourceMaritimeFixture.context(true, false, false).forShipment(fictionalShipment()).evidence())
			.extracting(MaritimeEvidence::evidenceId).containsExactly(SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID);
		assertThat(SyntheticMultiSourceMaritimeFixture.context(false, false, true).forShipment(fictionalShipment()).evidence())
			.extracting(MaritimeEvidence::evidenceId).containsExactly(SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID);
		assertThat(SyntheticMultiSourceMaritimeFixture.context(false, false, false).forShipment(fictionalShipment()).evidence()).isEmpty();
	}

	private static ShipmentDetailResponse fictionalShipment() { return shipment("9000002", "Synthetic Multi-source Port"); }
	private static ShipmentDetailResponse controlledReplayShipment() {
		return new ShipmentDetailResponse(200L, 99L, "DEMO-CONTROLLED-TEST", "Synthetic origin", "Synthetic destination", "Synthetic Multi-source Port",
				"Synthetic Multi-source Vessel", OffsetDateTime.parse("2026-10-01T05:00:00+08:00"), "9000002", null, null,
				"Synthetic Multi-source Feeder", OffsetDateTime.parse("2026-10-01T14:00:00+08:00"), null, null, null,
				Instant.EPOCH, 0L, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, ShipmentStatus.ACTIVE);
	}
	private static ShipmentDetailResponse unrelatedShipment() { return shipment("9000003", "Synthetic Multi-source Port"); }
	private static ShipmentDetailResponse shipment(String imo, String port) {
		return new ShipmentDetailResponse(199L, 99L, "SYNTHETIC-MULTI", "Synthetic origin", "Synthetic destination", port,
				"Synthetic Multi-source Vessel", OffsetDateTime.parse("2026-10-01T05:00:00+08:00"), imo, null, null,
				"Synthetic Multi-source Feeder", OffsetDateTime.parse("2026-10-01T14:00:00+08:00"), null, null, null,
				Instant.EPOCH, 0L, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, ShipmentStatus.ACTIVE);
	}
}
