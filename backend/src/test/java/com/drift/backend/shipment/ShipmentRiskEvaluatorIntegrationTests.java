package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

import jakarta.persistence.EntityManager;

/** Uses valid persisted schedules and the same connection risk as shipment detail. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentRiskEvaluatorIntegrationTests {

	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-10-15T08:00:00Z");

	private static final String PLANNED_HIGH = "Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.";
	private static final String REMAINING_HIGH = "Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.";
	private static final String PLANNED_LOW = "At least 24 hours to transfer cargo between vessels. The planned connection window is being used.";

	@Autowired EntityManager entityManager;
	@Autowired JdbcTemplate jdbc;
	@Autowired CompanyRepository companies;
	@Autowired ShipmentService shipmentService;
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
		assertThat(state.getLastExplanation()).isEqualTo(PLANNED_HIGH);
		assertThat(state.getLastTransitionSequence()).isZero();
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(shipment.getId())).isEmpty();
	}

	@Test
	void detailAndStoredRiskMatchForAUsableEstimateAndForThePlannedFallback() {
		Instant ingested = Instant.now();
		OffsetDateTime feeder = ingested.atOffset(ZoneOffset.UTC).plusHours(2);
		String mother = "Risk Mother " + UUID.randomUUID();
		Shipment shipment = scheduled(mother, feeder.minusHours(48), feeder, ShipmentRiskBaselineMode.SILENT_FIRST_VALID);

		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.BASELINED);
		ShipmentDetailResponse fallback = detail(shipment);
		assertThat(fallback.remainingConnectionWindow()).isNull();
		assertThat(fallback.risk().level()).isEqualTo(ShipmentRisk.Level.LOW);
		assertThat(fallback.risk().explanation()).isEqualTo(PLANNED_LOW);
		assertThat(state(shipment).getLastRiskLevel()).isEqualTo(fallback.risk().level());
		assertThat(state(shipment).getLastExplanation()).isEqualTo(fallback.risk().explanation());

		jdbc.update("""
				INSERT INTO vessel_observations (
					mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
				VALUES (?, ?, ?, ?, ?, ?::timestamptz, 'SEEDED')
				""", mmsi(), mother, new BigDecimal("1.26"), new BigDecimal("103.82"), new BigDecimal("12.375"),
				ingested.toString());

		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.RISE_TRANSITION);
		ShipmentDetailResponse estimated = detail(shipment);
		assertThat(estimated.remainingConnectionWindow()).isNotNull();
		assertThat(estimated.remainingConnectionWindow().status()).isEqualTo(RemainingConnectionWindow.Status.AVAILABLE);
		assertThat(estimated.risk().level()).isEqualTo(ShipmentRisk.Level.HIGH);
		assertThat(estimated.risk().explanation()).isEqualTo(REMAINING_HIGH);
		assertThat(state(shipment).getLastRiskLevel()).isEqualTo(estimated.risk().level());
		assertThat(state(shipment).getLastExplanation()).isEqualTo(estimated.risk().explanation());
		ShipmentRiskTransition rise = onlyTransition(shipment);
		assertThat(rise.getRiskLevel()).isEqualTo(estimated.risk().level());
		assertThat(rise.getExplanation()).isEqualTo(estimated.risk().explanation());
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

	private Shipment scheduled(String mother, OffsetDateTime arrival, OffsetDateTime feeder,
			ShipmentRiskBaselineMode baselineMode) {
		Shipment shipment = new Shipment(company, user, "RISK-EVAL-" + UUID.randomUUID(), "Singapore", "Rotterdam",
				"Singapore", mother, arrival, "Feeder", feeder);
		entityManager.persist(shipment);
		entityManager.flush();
		states.saveAndFlush(new ShipmentRiskState(shipment, baselineMode, Instant.now()));
		return shipment;
	}

	private ShipmentDetailResponse detail(Shipment shipment) {
		return shipmentService.get(new AuthenticatedUser(user.getId(), user.getEmail(), Role.FREIGHT_FORWARDER,
				Instant.parse("2030-01-01T00:00:00Z")), shipment.getId());
	}

	private static String mmsi() {
		long suffix = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 800_000_000L);
		return Long.toString(100_000_000L + suffix);
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
