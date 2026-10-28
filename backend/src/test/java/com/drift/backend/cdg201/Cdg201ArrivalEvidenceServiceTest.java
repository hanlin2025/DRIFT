package com.drift.backend.cdg201;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Cdg201ArrivalEvidenceServiceTest {
	private final Cdg201ArrivalEvidenceService service = new Cdg201ArrivalEvidenceService(new Cdg201MpaLocationReferenceLookup());

	@Test
	void preservesTheRecordedKotaLarisRevisionAndSimulationBoundary() {
		Cdg201ArrivalEvidence evidence = service.forShipment(TestShipments.kotaLaris());
		assertThat(evidence.vesselImo()).isEqualTo("9351048");
		assertThat(evidence.revisionMinutes()).isEqualTo(180);
		assertThat(evidence.sourceTimestampSemantics()).contains("Asia/Singapore");
		assertThat(evidence.revisedExpectedArrivalSgt()).isEqualTo("2026-10-29T02:00:00+08:00");
		assertThat(evidence.revisedExpectedArrivalUtc()).isEqualTo("2026-10-28T18:00:00+00:00");
		assertThat(evidence.locationReferences()).extracting(Cdg201MpaLocationReference::rawLocationCode)
				.containsExactly("SEA", "PEBGA");
		assertThat(evidence.locationReferences().get(0)).satisfies(reference -> {
			assertThat(reference.mappingStatus()).isEqualTo("UNKNOWN");
			assertThat(reference.officialDescription()).isEmpty();
		});
		assertThat(evidence.locationReferences().get(1)).satisfies(reference -> {
			assertThat(reference.officialDescription()).isEqualTo("PILOT EAST BOARD GRD A");
			assertThat(reference.derivedCategory()).isEqualTo("PILOT_GROUND");
			assertThat(reference.mappingStatus()).isEqualTo("PARTIAL");
		});
		assertThat(evidence.locationMappingStatus()).isEqualTo("UNKNOWN_MPA_CODE_NOT_MAPPED_TO_DRIFT_PORT");
		assertThat(evidence.shipmentAssociation()).isEqualTo("UNVERIFIED_IMO_ONLY");
		assertThat(evidence.simulatedScenario().originalConnectionMarginMinutes()).isEqualTo(780);
		assertThat(evidence.simulatedScenario().revisedConnectionMarginMinutes()).isEqualTo(600);
	}

	@Test
	void returnsNoEvidenceForAnUnrelatedOrMissingImo() {
		assertThat(service.forShipment(TestShipments.otherVessel())).isNull();
	}
}
