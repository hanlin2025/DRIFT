package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.shipment.CreateShipmentRequest;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;

class SyntheticDisruptionScenarioFixtureTest {
	@Test
	void buildsTheVersionedSharedVisitFromExistingShipmentAndAisFormats() {
		SyntheticDisruptionScenarioFixture.Scenario scenario = SyntheticDisruptionScenarioFixture.create("FixtureA1");

		assertThat(scenario.contractVersion()).isEqualTo("cdg279.synthetic-disruption.v1");
		assertThat(scenario.shipments()).hasSize(2);
		assertThat(scenario.shipments()).extracting(input -> input.shipment().shipmentReference())
				.containsExactly("DEMO-CDG279-FixtureA1-A", "DEMO-CDG279-FixtureA1-B");
		assertThat(scenario.shipments()).allSatisfy(input -> {
			CreateShipmentRequest shipment = input.shipment();
			assertThat(shipment.transshipmentPort()).isEqualTo("Synthetic Multi-source Port");
			assertThat(shipment.motherVessel()).isEqualTo(scenario.motherVessel());
			assertThat(shipment.motherVesselMmsi()).isEqualTo("990279001");
			assertThat(shipment.motherVesselImo()).isNull();
			assertThat(shipment.feederVessel()).isEqualTo(scenario.feederVessel());
			assertThat(shipment.feederVesselMmsi()).isEqualTo("990279002");
			assertThat(shipment.feederVesselImo()).isNull();
			assertThat(shipment.plannedMotherArrivalAt().toInstant()).isEqualTo(Instant.parse("2026-10-01T06:00:00Z"));
			assertThat(shipment.plannedFeederDepartureAt().toInstant()).isEqualTo(Instant.parse("2026-10-02T12:00:00Z"));
			assertThat(input.expectedTransshipmentPort()).isEqualTo(shipment.transshipmentPort());
		});
		assertThat(scenario.shipments()).extracting(input -> input.deadline().toInstant())
				.containsExactly(Instant.parse("2026-10-01T12:00:00Z"), Instant.parse("2026-10-02T10:00:00Z"));
		assertThat(scenario.visitAssociation().shipmentReferences())
				.containsExactly("DEMO-CDG279-FixtureA1-A", "DEMO-CDG279-FixtureA1-B");
		assertThat(scenario.visitAssociation().associationType()).isEqualTo("FIXTURE_ASSUMED_SHARED_MOTHER_TO_FEEDER_VISIT");
		assertThat(scenario.visitAssociation().limitation()).contains("not AIS evidence of cargo carriage");
		assertThat(scenario.assumptions()).anySatisfy(assumption -> assertThat(assumption).contains("zero ingestion lag"));
	}

	@Test
	void hasThreeStrictlyOrderedRawObservationStagesAndNoComputedOutputFields() {
		List<SyntheticDisruptionScenarioFixture.Stage> stages = SyntheticDisruptionScenarioFixture.create("Timeline1").stages();
		assertThat(stages).extracting(SyntheticDisruptionScenarioFixture.Stage::id)
				.containsExactly("baseline", "material-slowdown", "worsened-connection");
		assertThat(stages).extracting(SyntheticDisruptionScenarioFixture.Stage::cutoff)
				.containsExactly(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T04:00:00Z"),
						Instant.parse("2026-10-01T08:00:00Z"));

		assertThat(stages).allSatisfy(stage -> {
			assertThat(stage.mother().observedAt()).isEqualTo(stage.cutoff());
			assertThat(stage.mother().receivedAt()).isEqualTo(stage.cutoff());
			assertThat(stage.mother().position().ingestedAt()).isEqualTo(stage.cutoff());
			assertThat(stage.mother().position().positionValid()).isTrue();
			assertThat(stage.mother().position().navigationalStatus()).isZero();
			assertThat(stage.mother().position().courseOverGroundDegrees()).isEqualByComparingTo("180");
			assertThat(stage.mother().position().trueHeadingDegrees()).isEqualTo(180);
			assertThat(stage.mother().position().aisUtcSecond()).isZero();
			assertThat(stage.feeder().observedAt()).isEqualTo(stage.cutoff());
			assertThat(stage.feeder().receivedAt()).isEqualTo(stage.cutoff());
			assertThat(stage.feeder().position().ingestedAt()).isEqualTo(stage.cutoff());
			assertThat(stage.feeder().position().latitude()).isEqualByComparingTo("1.26");
			assertThat(stage.feeder().position().longitude()).isEqualByComparingTo("103.82");
			assertThat(stage.feeder().position().speedOverGroundKnots()).isEqualByComparingTo("0");
			assertThat(stage.feeder().position().navigationalStatus()).isEqualTo(5);
			assertThat(stage.feeder().position().courseOverGroundDegrees()).isNull();
			assertThat(stage.feeder().position().trueHeadingDegrees()).isNull();
		});
		assertThat(stages).extracting(stage -> stage.mother().position().latitude())
				.containsExactly(new BigDecimal("2.26"), new BigDecimal("1.86"), new BigDecimal("1.66"));
		assertThat(stages).extracting(stage -> stage.mother().position().speedOverGroundKnots())
				.containsExactly(new BigDecimal("10"), new BigDecimal("3"), new BigDecimal("1"));
		assertThat(stages).extracting(stage -> stage.evidence().size()).containsExactly(0, 1, 5);
	}

	@Test
	void carriesFictionalEvidenceWithExplicitProvenanceAndRoleScope() {
		SyntheticDisruptionScenarioFixture.Scenario scenario = SyntheticDisruptionScenarioFixture.create("Evidence1");
		List<MaritimeEvidence> evidence = scenario.stages().stream().flatMap(stage -> stage.evidence().stream()).toList();

		assertThat(evidence).extracting(MaritimeEvidence::evidenceId).containsExactly(
				"cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2", "cdg279-v1-notice-other-area-2",
				"cdg279-v1-stale-negative-2", "cdg279-v1-unknown-negative-2");
		assertThat(evidence).allSatisfy(row -> {
			assertThat(row.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION");
			assertThat(row.sourceIdentifier()).contains("CDG279-SYNTHETIC");
			assertThat(row.sourceUrl()).startsWith("https://example.invalid/cdg279/");
			assertThat(row.title()).contains("Synthetic");
			assertThat(row.citation()).contains("Synthetic");
			assertThat(row.sourceEventAt()).isNull();
			assertThat(row.retrievedAtUtc()).isNotNull();
			assertThat(row.humanReviewRequired()).isTrue();
			assertThat(row.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
			assertThat(row.limitation()).contains("Fictional", "not AIS evidence of cargo carriage");
		});
		assertThat(evidence.get(0).vesselIdentity()).isEqualTo(scenario.motherVessel());
		assertThat(evidence.get(0).vesselRole()).isEqualTo("MOTHER");
		assertThat(evidence.get(0).observationType()).isEqualTo("EXPECTED_ARRIVAL");
		assertThat(evidence.get(0).associationScope()).isEqualTo("VESSEL_OBSERVATION");
		assertThat(evidence.get(0).previousValue()).isEqualTo("2026-10-01T06:00:00Z");
		assertThat(evidence.get(0).currentValue()).isEqualTo("2026-10-01T15:00:00Z");
		assertThat(evidence.get(1).associationScope()).isEqualTo("PORT_CONTEXT");
		assertThat(evidence.get(2).associationScope()).isEqualTo("REGIONAL_CONTEXT");
		assertThat(evidence.get(3).compatibilityState()).isEqualTo("INCOMPATIBLE");
		assertThat(evidence.get(3).limitation()).contains("excluded from inference");
		assertThat(evidence.get(4).freshness()).isEqualTo("STALE");
		assertThat(evidence.get(4).evidenceSufficient()).isTrue();
		assertThat(evidence.get(5).freshness()).isEqualTo("UNKNOWN");
		assertThat(evidence.get(5).observedAt()).isNull();
		assertThat(evidence.get(5).evidenceSufficient()).isFalse();
	}

	@Test
	void stageEvidenceAdapterUsesTheFixtureCutoffAndExactShipmentVisit() {
		SyntheticDisruptionScenarioFixture.Scenario scenario = SyntheticDisruptionScenarioFixture.create("Adapter1");
		ShipmentDetailResponse shipmentA = detail(scenario, scenario.shipments().get(0));
		ShipmentDetailResponse shipmentB = detail(scenario, scenario.shipments().get(1));
		ShipmentDetailResponse wrongVisit = detail(scenario, scenario.shipments().get(0));
		wrongVisit = withReference(wrongVisit, "UNRELATED-CDG279-SHIPMENT");

		assertThat(scenario.contextForStage(scenario.stages().get(0)).forShipment(shipmentA).evidence()).isEmpty();
		assertThat(scenario.contextForStage(scenario.stages().get(1)).forShipment(shipmentA).evidence())
				.extracting(MaritimeEvidence::evidenceId).containsExactly("cdg279-v1-revision-1");
		List<MaritimeEvidence> stage2A = scenario.contextForStage(scenario.stages().get(2)).forShipment(shipmentA).evidence();
		List<MaritimeEvidence> stage2B = scenario.contextForStage(scenario.stages().get(2)).forShipment(shipmentB).evidence();
		assertThat(stage2A).containsExactlyElementsOf(stage2B);
		assertThat(stage2A).extracting(MaritimeEvidence::evidenceId).containsExactly(
				"cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2", "cdg279-v1-notice-other-area-2",
				"cdg279-v1-stale-negative-2", "cdg279-v1-unknown-negative-2");
		assertThat(scenario.contextForStage(scenario.stages().get(2)).forShipment(wrongVisit).evidence()).isEmpty();
		assertThat(stage2A).allSatisfy(row -> {
			assertThat(Instant.parse(row.retrievedAtUtc()).isAfter(scenario.stages().get(2).cutoff())).isFalse();
			if (row.observedAt() != null) assertThat(Instant.parse(row.observedAt()).isAfter(scenario.stages().get(2).cutoff())).isFalse();
		});
	}

	@Test
	void ledgerIsOrderedIdempotentForAnIdenticalLatestStageAndRejectsConflictsAndRewind() {
		SyntheticDisruptionScenarioFixture.Scenario scenario = SyntheticDisruptionScenarioFixture.create("Ledger1");
		SyntheticDisruptionScenarioFixture.StageLedger ledger = scenario.newLedger();
		SyntheticDisruptionScenarioFixture.Stage baseline = scenario.stages().get(0);
		SyntheticDisruptionScenarioFixture.Stage slowdown = scenario.stages().get(1);
		SyntheticDisruptionScenarioFixture.Stage worsened = scenario.stages().get(2);

		assertThatThrownBy(() -> ledger.record(slowdown)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("strictly increasing");
		assertThat(ledger.latestRecordedOrder()).isEqualTo(-1);
		assertThat(ledger.record(baseline)).isEqualTo(SyntheticDisruptionScenarioFixture.RecordResult.RECORDED);
		assertThat(ledger.record(baseline)).isEqualTo(SyntheticDisruptionScenarioFixture.RecordResult.IDEMPOTENT_DUPLICATE);

		AisPosition original = baseline.mother().position();
		AisPosition conflictingPosition = new AisPosition(original.mmsi(), original.vesselName(), original.latitude(), original.longitude(),
				new BigDecimal("9"), original.courseOverGroundDegrees(), original.trueHeadingDegrees(), original.navigationalStatus(),
				original.positionValid(), original.aisUtcSecond(), original.ingestedAt(), original.source());
		var conflictingObservation = new SyntheticDisruptionScenarioFixture.AisObservation("MOTHER", baseline.cutoff(),
				baseline.cutoff(), "SYNTHETIC_DEMONSTRATION", conflictingPosition);
		assertThatThrownBy(() -> ledger.record(baseline.withMother(conflictingObservation)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Conflicting duplicate AIS ingestion");
		assertThat(ledger.latestRecordedOrder()).isZero();

		ledger.record(slowdown);
		ledger.record(worsened);
		assertThat(ledger.latestRecordedOrder()).isEqualTo(2);
		assertThatThrownBy(() -> ledger.record(baseline)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Backward stage replay");
		assertThat(ledger.latestRecordedOrder()).isEqualTo(2);
	}

	@Test
	void invalidFutureObservationOrEvidenceIsRejectedBeforeTheLedgerAdvances() {
		SyntheticDisruptionScenarioFixture.Scenario scenario = SyntheticDisruptionScenarioFixture.create("Validation1");
		SyntheticDisruptionScenarioFixture.Stage baseline = scenario.stages().get(0);
		var futurePosition = new AisPosition(baseline.mother().position().mmsi(), baseline.mother().position().vesselName(),
				baseline.mother().position().latitude(), baseline.mother().position().longitude(), baseline.mother().position().speedOverGroundKnots(),
				baseline.mother().position().courseOverGroundDegrees(), baseline.mother().position().trueHeadingDegrees(), 0, true, 0,
				baseline.cutoff().plusSeconds(1), baseline.mother().position().source());
		var futureObservation = new SyntheticDisruptionScenarioFixture.AisObservation("MOTHER", baseline.cutoff().plusSeconds(1),
				baseline.cutoff().plusSeconds(1), "SYNTHETIC_DEMONSTRATION", futurePosition);
		SyntheticDisruptionScenarioFixture.StageLedger ledger = scenario.newLedger();
		assertThatThrownBy(() -> ledger.record(baseline.withMother(futureObservation)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("differ from the validated v1 fixture");
		assertThat(ledger.latestRecordedOrder()).isEqualTo(-1);

		List<SyntheticDisruptionScenarioFixture.Stage> invalidStages = new ArrayList<>(scenario.stages());
		SyntheticDisruptionScenarioFixture.Stage stage1 = invalidStages.get(1);
		MaritimeEvidence revision = stage1.evidence().get(0);
		MaritimeEvidence futureRevision = copyEvidence(revision, "2026-10-01T04:00:01Z", "2026-10-01T04:00:01Z");
		invalidStages.set(1, new SyntheticDisruptionScenarioFixture.Stage(stage1.id(), stage1.order(), stage1.cutoff(),
				stage1.mother(), stage1.feeder(), List.of(futureRevision)));
		SyntheticDisruptionScenarioFixture.Scenario invalidScenario = new SyntheticDisruptionScenarioFixture.Scenario(
				scenario.contractVersion(), scenario.scenarioId(), scenario.runId(), scenario.origin(), scenario.destination(),
				scenario.transshipmentPort(), scenario.motherVessel(), scenario.motherMmsi(), scenario.feederVessel(), scenario.feederMmsi(),
				scenario.plannedMotherArrivalAt(), scenario.plannedFeederDepartureAt(), scenario.shipments(), scenario.visitAssociation(),
				invalidStages, scenario.assumptions());
		assertThatThrownBy(invalidScenario::validate).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("available by its stage cutoff");
		assertThat(ledger.latestRecordedOrder()).isEqualTo(-1);
	}

	@Test
	void rejectsUnsupportedContractVersionAndInvalidRunIdentity() {
		assertThatThrownBy(() -> SyntheticDisruptionScenarioFixture.create("has spaces"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Run id");
		SyntheticDisruptionScenarioFixture.Scenario scenario = SyntheticDisruptionScenarioFixture.create("Version1");
		SyntheticDisruptionScenarioFixture.Scenario wrongVersion = new SyntheticDisruptionScenarioFixture.Scenario(
				"cdg279.synthetic-disruption.v0", scenario.scenarioId(), scenario.runId(), scenario.origin(), scenario.destination(),
				scenario.transshipmentPort(), scenario.motherVessel(), scenario.motherMmsi(), scenario.feederVessel(), scenario.feederMmsi(),
				scenario.plannedMotherArrivalAt(), scenario.plannedFeederDepartureAt(), scenario.shipments(), scenario.visitAssociation(),
				scenario.stages(), scenario.assumptions());
		assertThatThrownBy(wrongVersion::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported contract version");
	}

	private static MaritimeEvidence copyEvidence(MaritimeEvidence row, String observedAt, String retrievedAt) {
		return new MaritimeEvidence(row.sourceType(), row.sourceMode(), row.evidenceId(), row.title(), row.sourceIdentifier(), row.sourceUrl(),
				row.citation(), row.sourceEventAt(), observedAt, retrievedAt, row.freshness(), row.previousValue(), row.currentValue(),
				row.changeMagnitude(), row.activityCompatibility(), row.locationCompatibility(), row.timeCompatibility(),
				row.compatibilityState(), row.evidenceSufficient(), row.humanReviewRequired(), row.shipmentAssociationConfidence(),
				row.limitation(), row.vesselIdentity(), row.vesselRole(), row.observationType(), row.associationScope());
	}

	private static ShipmentDetailResponse detail(SyntheticDisruptionScenarioFixture.Scenario scenario,
			SyntheticDisruptionScenarioFixture.ShipmentInput input) {
		CreateShipmentRequest request = input.shipment();
		return new ShipmentDetailResponse(1L, 1L, request.shipmentReference(), request.origin(), request.destination(), request.transshipmentPort(),
				request.motherVessel(), request.plannedMotherArrivalAt(), request.motherVesselImo(), request.motherVesselMmsi(), request.motherVesselOperator(),
				request.feederVessel(), request.plannedFeederDepartureAt(), request.feederVesselImo(), request.feederVesselMmsi(), request.feederVesselOperator(),
				Instant.EPOCH, 0L, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, ShipmentStatus.ACTIVE);
	}

	private static ShipmentDetailResponse withReference(ShipmentDetailResponse detail, String reference) {
		return new ShipmentDetailResponse(detail.id(), detail.companyId(), reference, detail.origin(), detail.destination(), detail.transshipmentPort(),
				detail.motherVessel(), detail.plannedMotherArrivalAt(), detail.motherVesselImo(), detail.motherVesselMmsi(), detail.motherVesselOperator(),
				detail.feederVessel(), detail.plannedFeederDepartureAt(), detail.feederVesselImo(), detail.feederVesselMmsi(), detail.feederVesselOperator(),
				detail.createdAt(), detail.version(), detail.connectionWindow(), detail.remainingConnectionWindow(), detail.risk(), detail.assessmentExplanation(),
				detail.riskContributions(), detail.recommendation(), detail.motherVesselPosition(), detail.motherVesselNameWarning(),
				detail.feederVesselPosition(), detail.feederVesselNameWarning(), detail.motherOperationalState(), detail.feederOperationalState(),
				detail.aisFeed(), detail.portConditionComparison(), detail.arrivalComparison(), detail.transshipmentArrivalDeadline(), detail.delayComparison(),
				detail.importer(), detail.status());
	}
}
