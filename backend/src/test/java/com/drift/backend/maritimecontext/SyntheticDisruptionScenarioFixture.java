package com.drift.backend.maritimecontext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.shipment.CreateShipmentRequest;
import com.drift.backend.shipment.ShipmentDetailResponse;

/**
 * Normalized, test-only inputs for the CDG-279 synthetic disruption scenario.
 *
 * <p>This fixture wraps the application's existing shipment, AIS, and maritime-evidence DTOs.
 * It carries no ETA, risk, window, impact, or generated-assessment result. The visit association
 * is an explicit fixture assumption, not an inference from AIS or evidence.</p>
 */
public final class SyntheticDisruptionScenarioFixture {
	public static final String CONTRACT_VERSION = "cdg279.synthetic-disruption.v1";
	public static final String PORT = "Synthetic Multi-source Port";
	public static final String MOTHER_MMSI = "990279001";
	public static final String FEEDER_MMSI = "990279002";
	public static final Instant PLANNED_MOTHER_ARRIVAL = Instant.parse("2026-10-01T06:00:00Z");
	public static final Instant PLANNED_FEEDER_DEPARTURE = Instant.parse("2026-10-02T12:00:00Z");

	private static final BigDecimal PORT_LATITUDE = new BigDecimal("1.26");
	private static final BigDecimal PORT_LONGITUDE = new BigDecimal("103.82");
	private static final String SYNTHETIC = "SYNTHETIC_DEMONSTRATION";
	private static final String VISIT_LIMITATION = "Fictional verified association only: this fixture binds both shipment references to one assumed mother-to-feeder visit. It is not AIS evidence of cargo carriage, a real booking, or a real vessel identity.";

	private SyntheticDisruptionScenarioFixture() { }

	public static Scenario create(String runId) {
		if (runId == null || !runId.matches("[A-Za-z0-9]{1,24}")) {
			throw new IllegalArgumentException("Run id must contain 1 to 24 ASCII letters or digits.");
		}
		String motherName = "CDG279 SYNTHETIC MOTHER " + runId;
		String feederName = "CDG279 SYNTHETIC FEEDER " + runId;
		OffsetDateTime motherArrival = PLANNED_MOTHER_ARRIVAL.atOffset(ZoneOffset.UTC);
		OffsetDateTime feederDeparture = PLANNED_FEEDER_DEPARTURE.atOffset(ZoneOffset.UTC);
		List<ShipmentInput> shipments = List.of(
				new ShipmentInput(request("DEMO-CDG279-" + runId + "-A", motherName, feederName,
						motherArrival, feederDeparture), OffsetDateTime.parse("2026-10-01T12:00:00Z"), PORT),
				new ShipmentInput(request("DEMO-CDG279-" + runId + "-B", motherName, feederName,
						motherArrival, feederDeparture), OffsetDateTime.parse("2026-10-02T10:00:00Z"), PORT));

		List<Stage> stages = List.of(
				stage("baseline", 0, "2026-10-01T00:00:00Z", motherName, feederName, "2.26", "10", List.of()),
				stage("material-slowdown", 1, "2026-10-01T04:00:00Z", motherName, feederName, "1.86", "3",
						List.of(revisionEvidence(motherName))),
				stage("worsened-connection", 2, "2026-10-01T08:00:00Z", motherName, feederName, "1.66", "1",
						List.of(noticeEvidence(), weatherEvidence(), incompatibleNoticeEvidence(), staleEvidence(motherName), unknownEvidence())));

		return new Scenario(CONTRACT_VERSION, "CDG279-" + runId, runId, "Synthetic origin", "Synthetic destination", PORT,
				motherName, MOTHER_MMSI, feederName, FEEDER_MMSI, motherArrival, feederDeparture, shipments,
				new VisitAssociation("CDG279-" + runId, shipments.stream().map(input -> input.shipment().shipmentReference()).toList(),
						motherName, MOTHER_MMSI, feederName, FEEDER_MMSI, "FIXTURE_ASSUMED_SHARED_MOTHER_TO_FEEDER_VISIT", VISIT_LIMITATION),
				stages,
				List.of("Observed, received, and ingested times are equal for each constructed AIS report; zero ingestion lag is a simplifying assumption.",
						"The manifest binds both fictional shipments to the same mother-to-feeder visit; AIS does not establish cargo carriage.",
						"The planned feeder departure is the comparison boundary; no separate cargo cutoff or extra handling allowance is modeled.",
						"All evidence is fictional and contextual; no port or weather item claims to cause the mother-vessel slowdown."))
				.validate();
	}

	private static CreateShipmentRequest request(String reference, String mother, String feeder,
			OffsetDateTime motherArrival, OffsetDateTime feederDeparture) {
		return new CreateShipmentRequest(reference, "Synthetic origin", "Synthetic destination", PORT, mother,
				motherArrival, feeder, feederDeparture, null, MOTHER_MMSI, null, null, FEEDER_MMSI, null);
	}

	private static Stage stage(String id, int order, String time, String motherName, String feederName,
			String latitude, String speed, List<MaritimeEvidence> evidence) {
		Instant cutoff = Instant.parse(time);
		AisPosition mother = position(MOTHER_MMSI, motherName, latitude, "103.82", speed, 0, cutoff);
		AisPosition feeder = position(FEEDER_MMSI, feederName, "1.26", "103.82", "0", 5, cutoff);
		return new Stage(id, order, cutoff, observation("MOTHER", cutoff, mother),
				observation("FEEDER", cutoff, feeder), evidence);
	}

	private static AisPosition position(String mmsi, String name, String latitude, String longitude,
			String speed, int navigationalStatus, Instant ingestedAt) {
		return new AisPosition(mmsi, name, new BigDecimal(latitude), new BigDecimal(longitude), new BigDecimal(speed),
				navigationalStatus == 0 ? new BigDecimal("180") : null,
				navigationalStatus == 0 ? 180 : null, navigationalStatus, true, 0, ingestedAt, AisPositionSource.SEEDED);
	}

	private static AisObservation observation(String role, Instant time, AisPosition position) {
		return new AisObservation(role, time, time, SYNTHETIC, position);
	}

	private static MaritimeEvidence revisionEvidence(String motherName) {
		return evidence("MPA_ETA_REVISION", "cdg279-v1-revision-1", "Synthetic expected-arrival revision",
				"CDG279-SYNTHETIC-MPA-REVISION-1", "https://example.invalid/cdg279/revision-1",
				"Synthetic MPA-style revision 1; the record is fictional and is not an authoritative vessel ETA.",
				"2026-10-01T04:00:00Z", "2026-10-01T04:00:00Z", "FRESH", true, "2026-10-01T06:00:00Z", "2026-10-01T15:00:00Z", null,
				"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION",
				motherName, "The revised expected-arrival value is fictional and deliberately differs from the AIS-derived estimate; it does not replace or change risk.");
	}

	private static MaritimeEvidence noticeEvidence() {
		return evidence("MPA_OFFICIAL_NOTICE", "cdg279-v1-notice-2", "Synthetic port-area notice",
				"CDG279-SYNTHETIC-PORT-NOTICE-2", "https://example.invalid/cdg279/notice-2",
				"Synthetic port-area notice; fictional effective window 2026-10-01T08:00:00Z through 2026-10-02T12:00:00Z.",
				"2026-10-01T08:00:00Z", "2026-10-01T08:00:00Z", "FRESH", true, null, "Synthetic port-area notice effective from 2026-10-01T08:00:00Z through 2026-10-02T12:00:00Z.", null,
				"UNKNOWN", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", null, null, "PORT_CONTEXT",
				null, "Fictional port-area context only. The effective window is not a publication time, and this notice does not establish vessel impact or causation.");
	}

	private static MaritimeEvidence weatherEvidence() {
		return evidence("NEA_WEATHER", "cdg279-v1-weather-2", "Synthetic regional weather context",
				"CDG279-SYNTHETIC-WEATHER-2", "https://example.invalid/cdg279/weather-2",
				"Synthetic regional weather context for the fictional port area; no quantitative weather value is asserted.",
				"2026-10-01T08:00:00Z", "2026-10-01T08:00:00Z", "FRESH", true, null, "Synthetic regional context observed at the stage 2 cutoff.", null,
				"UNKNOWN", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", null, null, "REGIONAL_CONTEXT",
				null, "Fictional regional context only. It does not establish vessel-specific conditions or cause the slowdown.");
	}

	private static MaritimeEvidence incompatibleNoticeEvidence() {
		return evidence("MPA_OFFICIAL_NOTICE", "cdg279-v1-notice-other-area-2", "Synthetic notice for a different area",
				"CDG279-SYNTHETIC-OTHER-AREA-NOTICE-2", "https://example.invalid/cdg279/notice-other-area-2",
				"Synthetic notice for a different fictional area; retained as an incompatibility control only.",
				"2026-10-01T08:00:00Z", "2026-10-01T08:00:00Z", "FRESH", true, null, "Different Synthetic Area; fictional effective window 2026-10-01T08:00:00Z through 2026-10-02T12:00:00Z.", null,
				"INCOMPATIBLE", "INCOMPATIBLE", "COMPATIBLE", "INCOMPATIBLE", null, null, "PORT_CONTEXT",
				null, "Fictional negative control. It is incompatible with this visit and must be excluded from inference.");
	}

	private static MaritimeEvidence staleEvidence(String motherName) {
		return evidence("MPA_ETA_REVISION", "cdg279-v1-stale-negative-2", "Synthetic stale expected-arrival control",
				"CDG279-SYNTHETIC-STALE-REVISION-2", "https://example.invalid/cdg279/stale-revision-2",
				"Synthetic old expected-arrival control retained to verify stale evidence exclusion.",
				"2026-10-01T01:00:00Z", "2026-10-01T08:00:00Z", "STALE", true, "2026-10-01T06:00:00Z", "2026-10-01T15:00:00Z", null,
				"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION",
				motherName, "Fictional stale negative control. Its observation is seven hours before the cutoff and it is excluded from inference.");
	}

	private static MaritimeEvidence unknownEvidence() {
		return new MaritimeEvidence("NEA_WEATHER", SYNTHETIC, "cdg279-v1-unknown-negative-2", "Synthetic weather with unknown observation time",
				"CDG279-SYNTHETIC-UNKNOWN-WEATHER-2", "https://example.invalid/cdg279/unknown-weather-2",
				"Synthetic regional weather control with no known observation time.", null, null, "2026-10-01T08:00:00Z", "UNKNOWN",
				null, "Regional context only; observation time unknown.", null, "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN",
				false, true, "VERIFIED", "Fictional unknown-freshness negative control; not eligible as assessment evidence. " + VISIT_LIMITATION,
				null, null, null, "REGIONAL_CONTEXT");
	}

	private static MaritimeEvidence evidence(String sourceType, String evidenceId, String title, String sourceIdentifier,
			String sourceUrl, String citation, String observedAt, String retrievedAt, String freshness, boolean sufficient,
			String previousValue, String currentValue,
			String changeMagnitude, String activityCompatibility, String locationCompatibility,
			String timeCompatibility, String compatibilityState, String vesselRole, String observationType,
			String associationScope, String vesselIdentity, String limitation) {
		return new MaritimeEvidence(sourceType, SYNTHETIC, evidenceId, title, sourceIdentifier, sourceUrl, citation,
				null, observedAt, retrievedAt, freshness, previousValue, currentValue, changeMagnitude,
				activityCompatibility, locationCompatibility, timeCompatibility, compatibilityState,
				sufficient, true, "VERIFIED", limitation + " " + VISIT_LIMITATION,
				vesselIdentity, vesselRole, observationType, associationScope);
	}

	public record ShipmentInput(CreateShipmentRequest shipment, OffsetDateTime deadline, String expectedTransshipmentPort) {
		public ShipmentInput {
			Objects.requireNonNull(shipment, "shipment");
			Objects.requireNonNull(deadline, "deadline");
			if (expectedTransshipmentPort == null || expectedTransshipmentPort.isBlank()) {
				throw new IllegalArgumentException("Expected transshipment port is required.");
			}
		}
	}

	/** Full observed/received time and provenance live beside the existing AIS DTO. */
	public record AisObservation(String vesselRole, Instant observedAt, Instant receivedAt,
			String sourceMode, AisPosition position) { }

	public record Stage(String id, int order, Instant cutoff, AisObservation mother, AisObservation feeder,
			List<MaritimeEvidence> evidence) {
		public Stage {
			evidence = evidence == null ? List.of() : List.copyOf(evidence);
		}

		public Stage withMother(AisObservation replacement) {
			return new Stage(id, order, cutoff, replacement, feeder, evidence);
		}
//
	}

	public record VisitAssociation(String scenarioId, List<String> shipmentReferences, String motherVessel,
			String motherMmsi, String feederVessel, String feederMmsi, String associationType, String limitation) {
		public VisitAssociation {
			shipmentReferences = List.copyOf(shipmentReferences);
		}
	}

	public record Scenario(String contractVersion, String scenarioId, String runId, String origin, String destination,
			String transshipmentPort, String motherVessel, String motherMmsi, String feederVessel, String feederMmsi,
			OffsetDateTime plannedMotherArrivalAt, OffsetDateTime plannedFeederDepartureAt,
			List<ShipmentInput> shipments, VisitAssociation visitAssociation, List<Stage> stages, List<String> assumptions) {
		public Scenario {
			shipments = List.copyOf(shipments);
			stages = List.copyOf(stages);
			assumptions = List.copyOf(assumptions);
		}

		public Scenario validate() {
			if (!CONTRACT_VERSION.equals(contractVersion) || runId == null || !runId.matches("[A-Za-z0-9]{1,24}") || scenarioId == null
					|| !scenarioId.equals("CDG279-" + runId)) throw invalid("Unsupported contract version or scenario identity.");
			if (shipments.size() != 2 || stages.size() != 3) throw invalid("The scenario requires two shipments and three stages.");
			if (!"Synthetic origin".equals(origin) || !"Synthetic destination".equals(destination)
					|| !PORT.equals(transshipmentPort) || !("CDG279 SYNTHETIC MOTHER " + runId).equals(motherVessel)
					|| !MOTHER_MMSI.equals(motherMmsi) || !("CDG279 SYNTHETIC FEEDER " + runId).equals(feederVessel)
					|| !FEEDER_MMSI.equals(feederMmsi) || !Instant.parse("2026-10-01T06:00:00Z").equals(plannedMotherArrivalAt.toInstant())
					|| !Instant.parse("2026-10-02T12:00:00Z").equals(plannedFeederDepartureAt.toInstant())) {
				throw invalid("The shared itinerary does not match the v1 contract.");
			}
			validateShipments();
			validateVisitAssociation();
			Set<String> evidenceIds = new HashSet<>();
			Instant previousCutoff = null;
			for (int index = 0; index < stages.size(); index++) {
				Stage stage = stages.get(index);
				validateStage(stage, index);
				if (previousCutoff != null && !stage.cutoff().isAfter(previousCutoff)) throw invalid("Stage cutoffs must be strictly increasing.");
				previousCutoff = stage.cutoff();
				for (MaritimeEvidence row : stage.evidence()) {
					validateEvidence(row, stage.cutoff());
					if (!evidenceIds.add(row.evidenceId())) throw invalid("Evidence ids must be unique within a scenario run.");
				}
			}
			return this;
		}

		private void validateShipments() {
			Set<String> refs = new HashSet<>();
			for (int index = 0; index < shipments.size(); index++) {
				ShipmentInput input = shipments.get(index);
				CreateShipmentRequest shipment = input.shipment();
				String suffix = index == 0 ? "A" : "B";
				if (!("DEMO-CDG279-" + runId + "-" + suffix).equals(shipment.shipmentReference())
						|| !refs.add(shipment.shipmentReference()) || !origin.equals(shipment.origin())
						|| !destination.equals(shipment.destination()) || !transshipmentPort.equals(shipment.transshipmentPort())
						|| !motherVessel.equals(shipment.motherVessel()) || !motherMmsi.equals(shipment.motherVesselMmsi())
						|| !feederVessel.equals(shipment.feederVessel()) || !feederMmsi.equals(shipment.feederVesselMmsi())
						|| !plannedMotherArrivalAt.equals(shipment.plannedMotherArrivalAt())
						|| !plannedFeederDepartureAt.equals(shipment.plannedFeederDepartureAt())
						|| shipment.motherVesselImo() != null || shipment.feederVesselImo() != null
						|| !transshipmentPort.equals(input.expectedTransshipmentPort())) {
					throw invalid("Both shipments must use the shared v1 itinerary and omit unknown identities.");
				}
			}
			if (!shipments.get(0).deadline().toInstant().equals(Instant.parse("2026-10-01T12:00:00Z"))
					|| !shipments.get(1).deadline().toInstant().equals(Instant.parse("2026-10-02T10:00:00Z"))) {
				throw invalid("Shipment-specific v1 deadlines do not match the contract.");
			}
		}

		private void validateVisitAssociation() {
			if (visitAssociation == null || !scenarioId.equals(visitAssociation.scenarioId())
					|| !visitAssociation.shipmentReferences().equals(shipments.stream().map(input -> input.shipment().shipmentReference()).toList())
					|| !motherVessel.equals(visitAssociation.motherVessel()) || !motherMmsi.equals(visitAssociation.motherMmsi())
					|| !feederVessel.equals(visitAssociation.feederVessel()) || !feederMmsi.equals(visitAssociation.feederMmsi())
					|| !"FIXTURE_ASSUMED_SHARED_MOTHER_TO_FEEDER_VISIT".equals(visitAssociation.associationType())
					|| visitAssociation.limitation() == null || !visitAssociation.limitation().contains("not AIS evidence")) {
				throw invalid("The shared visit assumption must explicitly bind both shipments and retain its limitation.");
			}
		}

		private void validateStage(Stage stage, int expectedOrder) {
			if (stage == null || stage.order() != expectedOrder || stage.id() == null || stage.id().isBlank()
					|| stage.cutoff() == null || stage.mother() == null || stage.feeder() == null) throw invalid("Stage identity, order, cutoff, and vessel rows are required.");
			String[] expectedIds = { "baseline", "material-slowdown", "worsened-connection" };
			Instant[] expectedCutoffs = { Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T04:00:00Z"), Instant.parse("2026-10-01T08:00:00Z") };
			String[] expectedLatitudes = { "2.26", "1.86", "1.66" };
			String[] expectedSpeeds = { "10", "3", "1" };
			if (!expectedIds[expectedOrder].equals(stage.id()) || !expectedCutoffs[expectedOrder].equals(stage.cutoff())
					|| stage.mother().position() == null
					|| stage.mother().position().latitude().compareTo(new BigDecimal(expectedLatitudes[expectedOrder])) != 0
					|| stage.mother().position().speedOverGroundKnots().compareTo(new BigDecimal(expectedSpeeds[expectedOrder])) != 0) {
				throw invalid("Stage identity, clock, or mother observation differs from the v1 inputs.");
			}
			validateObservation(stage.mother(), "MOTHER", stage.cutoff());
			validateObservation(stage.feeder(), "FEEDER", stage.cutoff());
			if (stage.evidence().stream().anyMatch(Objects::isNull)) throw invalid("Evidence rows cannot be null.");
			List<String> expectedEvidence = switch (expectedOrder) {
				case 0 -> List.of();
				case 1 -> List.of("cdg279-v1-revision-1");
				case 2 -> List.of("cdg279-v1-notice-2", "cdg279-v1-weather-2", "cdg279-v1-notice-other-area-2",
						"cdg279-v1-stale-negative-2", "cdg279-v1-unknown-negative-2");
				default -> throw invalid("Unknown stage order.");
			};
		if (!expectedEvidence.equals(stage.evidence().stream().map(MaritimeEvidence::evidenceId).toList())) {
				throw invalid("Evidence rows differ from the v1 stage inputs.");
			}
		}

		private void validateEvidence(MaritimeEvidence row, Instant cutoff) {
			if (!SYNTHETIC.equals(row.sourceMode()) || row.evidenceId() == null || !row.evidenceId().startsWith("cdg279-v1-")
					|| isBlank(row.title()) || isBlank(row.sourceIdentifier()) || isBlank(row.citation())
					|| row.sourceUrl() == null || !row.sourceUrl().startsWith("https://example.invalid/cdg279/")
				|| row.limitation() == null || !row.limitation().contains("Fictional")
				|| row.freshness() == null || !Set.of("FRESH", "STALE", "UNKNOWN").contains(row.freshness()) || !row.humanReviewRequired()
					|| !"VERIFIED".equals(row.shipmentAssociationConfidence()) || row.sourceEventAt() != null
					|| row.retrievedAtUtc() == null
					|| row.observedAt() != null && parseInstant(row.observedAt()).isAfter(cutoff)
					|| parseInstant(row.retrievedAtUtc()).isAfter(cutoff)) {
				throw invalid("Evidence row " + row.evidenceId() + " must have complete synthetic provenance and be available by its stage cutoff.");
			}
			if ("FRESH".equals(row.freshness()) && (!row.evidenceSufficient() || row.observedAt() == null
					|| !parseInstant(row.observedAt()).equals(cutoff) || !parseInstant(row.retrievedAtUtc()).equals(cutoff))) {
				throw invalid("Fresh evidence row " + row.evidenceId() + " must be sufficient and observed/retrieved at the stage cutoff.");
			}
			if ("cdg279-v1-revision-1".equals(row.evidenceId())) {
				if (!"MPA_ETA_REVISION".equals(row.sourceType()) || !"MOTHER".equals(row.vesselRole())
						|| !"EXPECTED_ARRIVAL".equals(row.observationType()) || !"VESSEL_OBSERVATION".equals(row.associationScope())
						|| !motherVessel.equals(row.vesselIdentity())
						|| !"2026-10-01T06:00:00Z".equals(row.previousValue()) || !"2026-10-01T15:00:00Z".equals(row.currentValue())) {
					throw invalid("The stage 1 revision must preserve its expected-arrival role and values.");
				}
			} else if ("cdg279-v1-notice-2".equals(row.evidenceId())) {
				if (!"MPA_OFFICIAL_NOTICE".equals(row.sourceType()) || !"PORT_CONTEXT".equals(row.associationScope())) throw invalid("The stage 2 notice must use port-context scope.");
			} else if ("cdg279-v1-weather-2".equals(row.evidenceId())) {
				if (!"NEA_WEATHER".equals(row.sourceType()) || !"REGIONAL_CONTEXT".equals(row.associationScope())) throw invalid("The stage 2 weather must use regional-context scope.");
			} else if ("cdg279-v1-notice-other-area-2".equals(row.evidenceId())) {
				if (!"INCOMPATIBLE".equals(row.compatibilityState())) throw invalid("The other-area notice must remain incompatible.");
			} else if ("cdg279-v1-stale-negative-2".equals(row.evidenceId())) {
				if (!"STALE".equals(row.freshness()) || row.evidenceSufficient() == false || row.observedAt() == null
						|| !Instant.parse("2026-10-01T01:00:00Z").equals(parseInstant(row.observedAt()))) throw invalid("The stale negative control must be over six hours old at stage 2.");
			} else if ("cdg279-v1-unknown-negative-2".equals(row.evidenceId())) {
				if (!"UNKNOWN".equals(row.freshness()) || row.evidenceSufficient() || row.observedAt() != null) throw invalid("The unknown negative control must have no asserted observation time and be insufficient.");
			} else {
				throw invalid("Unexpected evidence id for the v1 contract.");
			}
		}

		private void validateObservation(AisObservation observation, String role, Instant cutoff) {
			AisPosition position = observation.position();
			if (!role.equals(observation.vesselRole()) || !SYNTHETIC.equals(observation.sourceMode())
					|| observation.observedAt() == null || observation.receivedAt() == null || position == null
					|| !observation.observedAt().equals(observation.receivedAt())
					|| !observation.receivedAt().equals(position.ingestedAt()) || !observation.receivedAt().equals(cutoff)
					|| !Boolean.TRUE.equals(position.positionValid()) || position.aisUtcSecond() == null || position.aisUtcSecond() != 0
					|| position.latitude() == null || position.latitude().compareTo(BigDecimal.valueOf(-90)) < 0
					|| position.latitude().compareTo(BigDecimal.valueOf(90)) > 0 || position.longitude() == null
					|| position.longitude().compareTo(BigDecimal.valueOf(-180)) < 0 || position.longitude().compareTo(BigDecimal.valueOf(180)) > 0
					|| position.speedOverGroundKnots() == null || position.speedOverGroundKnots().signum() < 0
					|| position.source() != AisPositionSource.SEEDED) {
				throw invalid("AIS observation provenance, time, coordinates, position validity, or speed is invalid.");
			}
			if ("MOTHER".equals(role) && (!motherMmsi.equals(position.mmsi()) || !motherVessel.equals(position.vesselName())
					|| position.longitude().compareTo(PORT_LONGITUDE) != 0 || !Integer.valueOf(0).equals(position.navigationalStatus())
					|| !new BigDecimal("180").equals(position.courseOverGroundDegrees())
					|| !Integer.valueOf(180).equals(position.trueHeadingDegrees()))) throw invalid("Mother AIS identity or underway fields do not match the contract.");
			if ("FEEDER".equals(role) && (!feederMmsi.equals(position.mmsi()) || !feederVessel.equals(position.vesselName())
					|| !Integer.valueOf(5).equals(position.navigationalStatus()) || position.courseOverGroundDegrees() != null
					|| position.trueHeadingDegrees() != null || position.latitude().compareTo(PORT_LATITUDE) != 0
					|| position.longitude().compareTo(PORT_LONGITUDE) != 0 || position.speedOverGroundKnots().signum() != 0)) {
				throw invalid("Feeder AIS identity or moored-at-port fields do not match the contract.");
			}
		}

		public MaritimeContextService contextForStage(Stage stage) {
			Stage canonical = stageByOrder(stage.order());
			if (!canonical.equals(stage)) throw invalid("Context can only be created for a validated fixture stage.");
			return contextForClock(Clock.fixed(stage.cutoff(), ZoneOffset.UTC));
		}

		/** Uses the same controlled test clock as shipment risk for an authenticated replay harness. */
		public MaritimeContextService contextForClock(Clock replayClock) {
			Objects.requireNonNull(replayClock, "replayClock");
			MaritimeEvidenceSourceAdapter adapter = (shipment, cutoff) -> {
				if (!matchesVisit(shipment)) return List.of();
				return stages.stream().flatMap(item -> item.evidence().stream())
						.filter(row -> !parseInstant(row.retrievedAtUtc()).isAfter(cutoff))
						.filter(row -> row.observedAt() == null || !parseInstant(row.observedAt()).isAfter(cutoff))
						.toList();
			};
			return new MaritimeContextService(true, List.of(adapter), replayClock);
		}

		public StageLedger newLedger() { return new StageLedger(this); }

		private Stage stageByOrder(int order) {
			if (order < 0 || order >= stages.size()) throw invalid("Unknown scenario stage.");
			return stages.get(order);
		}

		private boolean matchesVisit(ShipmentDetailResponse shipment) {
			if (shipment == null || visitAssociation.shipmentReferences().stream().noneMatch(shipment.shipmentReference()::equals)) return false;
			return Objects.equals(transshipmentPort, shipment.transshipmentPort())
					&& Objects.equals(motherVessel, shipment.motherVessel()) && Objects.equals(motherMmsi, shipment.motherVesselMmsi())
					&& Objects.equals(feederVessel, shipment.feederVessel()) && Objects.equals(feederMmsi, shipment.feederVesselMmsi())
					&& Objects.equals(plannedMotherArrivalAt, shipment.plannedMotherArrivalAt())
					&& Objects.equals(plannedFeederDepartureAt, shipment.plannedFeederDepartureAt());
		}
	}

	/** Enforces the fixture's strictly increasing replay order and duplicate-ingestion contract. */
	public static final class StageLedger {
		private final Scenario scenario;
		private final Map<Integer, Stage> recordedStages = new HashMap<>();
		private final Map<ObservationKey, AisObservation> ingestedObservations = new HashMap<>();
		private int latestOrder = -1;

		private StageLedger(Scenario scenario) { this.scenario = scenario; }

		public synchronized RecordResult record(Stage stage) {
			if (stage == null || stage.order() < 0 || stage.order() >= scenario.stages().size()) throw invalid("Unknown stage cannot be recorded.");
			Stage existing = recordedStages.get(stage.order());
			if (existing != null) {
				if (stage.order() < latestOrder) throw invalid("Backward stage replay is not allowed.");
				if (existing.equals(stage)) return RecordResult.IDEMPOTENT_DUPLICATE;
				assertNoConflictingDuplicate(existing.mother(), stage.mother());
				assertNoConflictingDuplicate(existing.feeder(), stage.feeder());
				throw invalid(stage.order() < latestOrder ? "Backward stage replay is not allowed." : "Conflicting duplicate stage is not allowed.");
			}
			if (stage.order() != latestOrder + 1) throw invalid("Stages must be recorded once in strictly increasing order.");
			Stage expected = scenario.stages().get(stage.order());
			if (!expected.equals(stage)) throw invalid("Stage inputs differ from the validated v1 fixture; nothing was recorded.");
			scenario.validateStage(stage, stage.order());
			acceptObservation(stage.mother());
			acceptObservation(stage.feeder());
			recordedStages.put(stage.order(), stage);
			latestOrder = stage.order();
			return RecordResult.RECORDED;
		}

		public synchronized int latestRecordedOrder() { return latestOrder; }

		private void acceptObservation(AisObservation observation) {
			ObservationKey key = new ObservationKey(observation.position().mmsi(), observation.receivedAt());
			AisObservation previous = ingestedObservations.putIfAbsent(key, observation);
			if (previous != null && !previous.equals(observation)) throw invalid("Conflicting duplicate AIS ingestion is not allowed.");
		}

		private void assertNoConflictingDuplicate(AisObservation previous, AisObservation incoming) {
			if (previous == null || incoming == null) return;
			if (Objects.equals(previous.position().mmsi(), incoming.position().mmsi())
					&& Objects.equals(previous.receivedAt(), incoming.receivedAt()) && !previous.equals(incoming)) {
				throw invalid("Conflicting duplicate AIS ingestion is not allowed.");
			}
		}
	}

	public enum RecordResult { RECORDED, IDEMPOTENT_DUPLICATE }

	private record ObservationKey(String mmsi, Instant receivedAt) { }

	private static boolean isBlank(String value) { return value == null || value.isBlank(); }

	private static Instant parseInstant(String value) {
		try { return Instant.parse(value); }
		catch (RuntimeException ignored) { return OffsetDateTime.parse(value).toInstant(); }
	}

	private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
