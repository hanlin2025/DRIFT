package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

import jakarta.persistence.EntityManager;

/** Uses valid persisted schedules and the production ConnectionWindowService. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentRiskEvaluatorIntegrationTests {

	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-10-15T08:00:00Z");

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired ShipmentRiskEvaluator evaluator;
	@Autowired ShipmentRiskStateRepository states;
	@Autowired ShipmentRiskTransitionRepository transitions;

	private Company company;
	private UserAccount user;

	@BeforeEach
	void setUp() {
		company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
		user = new UserAccount("Risk Evaluator", "risk-evaluator-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		entityManager.persist(user);
		entityManager.flush();
	}

	@Test
	void silentlyBaselinesHistoricalFirstValidRisk() {
		Shipment shipment = shipment(8, ShipmentRiskBaselineMode.SILENT_FIRST_VALID);

		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.BASELINED);

		ShipmentRiskState state = state(shipment);
		assertThat(state.getLastRiskLevel()).isEqualTo(ShipmentRisk.Level.HIGH);
		assertThat(state.getLastExplanation()).isEqualTo("Less than 12 hours to transfer cargo between vessels.");
		assertThat(state.getLastTransitionSequence()).isZero();
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(shipment.getId())).isEmpty();
	}

	@Test
	void createsOneInitialTransitionForAnAlertableFirstValidRiskIncludingLow() {
		Shipment shipment = shipment(24, ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID);

		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.INITIAL_TRANSITION);
		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.NO_TRANSITION);

		ShipmentRiskTransition transition = onlyTransition(shipment);
		assertThat(transition.getTransitionType()).isEqualTo(ShipmentRiskTransitionType.INITIAL);
		assertThat(transition.getPreviousRiskLevel()).isNull();
		assertThat(transition.getRiskLevel()).isEqualTo(ShipmentRisk.Level.LOW);
		assertThat(transition.getSequence()).isOne();
		assertThat(state(shipment).getLastTransitionSequence()).isOne();
	}

	@Test
	void createsRisesForValidScheduleRiskIncreasesAndRecordsDecreases() {
		Shipment shipment = shipment(24, ShipmentRiskBaselineMode.SILENT_FIRST_VALID);
		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.BASELINED);

		setWindow(shipment, 20);
		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.RISE_TRANSITION);
		setWindow(shipment, 8);
		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.RISE_TRANSITION);
		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.NO_TRANSITION);
		setWindow(shipment, 24);
		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.NO_TRANSITION);
		setWindow(shipment, 8);
		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.RISE_TRANSITION);

		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(shipment.getId()))
				.extracting(ShipmentRiskTransition::getPreviousRiskLevel, ShipmentRiskTransition::getRiskLevel)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(ShipmentRisk.Level.LOW, ShipmentRisk.Level.MEDIUM),
						org.assertj.core.groups.Tuple.tuple(ShipmentRisk.Level.MEDIUM, ShipmentRisk.Level.HIGH),
						org.assertj.core.groups.Tuple.tuple(ShipmentRisk.Level.LOW, ShipmentRisk.Level.HIGH));
		assertThat(state(shipment).getLastTransitionSequence()).isEqualTo(3);
	}

	@Test
	void silentlyRecoversMissingStateAndSkipsArchivedShipments() {
		Shipment missing = shipmentWithoutState(hours(24));
		assertThat(evaluator.evaluate(missing.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.BASELINED);
		assertThat(state(missing).getBaselineMode()).isEqualTo(ShipmentRiskBaselineMode.SILENT_FIRST_VALID);
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(missing.getId())).isEmpty();

		Shipment archived = shipment(8, ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID);
		archived.archive();
		entityManager.flush();
		assertThat(evaluator.evaluate(archived.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.ARCHIVED);
		assertThat(states.findById(archived.getId())).isPresent();
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(archived.getId())).isEmpty();
	}

	private Shipment shipment(int durationHours, ShipmentRiskBaselineMode baselineMode) {
		Shipment shipment = shipmentWithoutState(hours(durationHours));
		states.saveAndFlush(new ShipmentRiskState(shipment, baselineMode, java.time.Instant.now()));
		return shipment;
	}

	private Shipment shipmentWithoutState(OffsetDateTime feederDeparture) {
		Shipment shipment = new Shipment(company, user, "RISK-EVAL-" + UUID.randomUUID(), "Singapore", "Rotterdam", "Singapore",
				"Mother", ARRIVAL, "Feeder", feederDeparture);
		entityManager.persist(shipment);
		entityManager.flush();
		return shipment;
	}

	private ShipmentRiskState state(Shipment shipment) {
		entityManager.flush();
		entityManager.clear();
		return states.findById(shipment.getId()).orElseThrow();
	}

	private ShipmentRiskTransition onlyTransition(Shipment shipment) {
		entityManager.flush();
		entityManager.clear();
		return transitions.findByShipmentIdOrderBySequenceAsc(shipment.getId()).getFirst();
	}

	private void setWindow(Shipment shipment, int durationHours) {
		shipment.replaceDetails(new ShipmentDetails(shipment.getShipmentReference(), shipment.getOrigin(), shipment.getDestination(),
				shipment.getTransshipmentPort(), shipment.getMotherVessel(), ARRIVAL, shipment.getFeederVessel(), hours(durationHours),
				null, null, null, null, null, null), user, java.time.Instant.now());
		entityManager.flush();
	}

	private static OffsetDateTime hours(int durationHours) {
		return ARRIVAL.plusHours(durationHours);
	}
}
