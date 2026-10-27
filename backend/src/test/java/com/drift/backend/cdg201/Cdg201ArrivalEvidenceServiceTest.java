package com.drift.backend.cdg201;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Cdg201ArrivalEvidenceServiceTest {
	private final Cdg201ArrivalEvidenceService service = new Cdg201ArrivalEvidenceService();

	@Test
	void preservesTheRecordedKotaLarisRevisionAndSimulationBoundary() {
		Cdg201ArrivalEvidence evidence = service.forShipment(TestShipments.kotaLaris());
		assertThat(evidence.vesselImo()).isEqualTo("9351048");
		assertThat(evidence.revisionMinutes()).isEqualTo(180);
		assertThat(evidence.sourceTimestampSemantics()).contains("undocumented");
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
