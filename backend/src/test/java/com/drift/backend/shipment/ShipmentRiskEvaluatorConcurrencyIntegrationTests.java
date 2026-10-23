package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

import jakarta.persistence.EntityManager;

/** Verifies the CDG-193 state-row lock serializes concurrent evaluations of one shipment. */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentRiskEvaluatorConcurrencyIntegrationTests {

	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-10-15T08:00:00Z");

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired ShipmentRiskEvaluator evaluator;
	@Autowired ShipmentRiskStateRepository states;
	@Autowired ShipmentRiskTransitionRepository transitions;
	@Autowired PlatformTransactionManager transactionManager;
	private Long shipmentId;

	@Test
	void concurrentEvaluationsCreateOnlyOneTransitionForTheSameRise() throws Exception {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		shipmentId = transaction.execute(status -> createLowBaselineThenRaiseToHigh());

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			try {
				Future<ShipmentRiskEvaluationOutcome> first = workers.submit(() -> evaluateWhenReleased(ready, start));
				Future<ShipmentRiskEvaluationOutcome> second = workers.submit(() -> evaluateWhenReleased(ready, start));
				ready.await();
				start.countDown();

				List<ShipmentRiskEvaluationOutcome> outcomes = List.of(first.get(), second.get());
				assertThat(outcomes).containsExactlyInAnyOrder(ShipmentRiskEvaluationOutcome.RISE_TRANSITION,
						ShipmentRiskEvaluationOutcome.NO_TRANSITION);
			} finally {
				workers.shutdownNow();
			}

			transaction.executeWithoutResult(status -> {
				ShipmentRiskState state = states.findById(shipmentId).orElseThrow();
				assertThat(state.getLastRiskLevel()).isEqualTo(ShipmentRisk.Level.HIGH);
				assertThat(state.getLastTransitionSequence()).isOne();
				assertThat(transitions.findByShipmentIdOrderBySequenceAsc(shipmentId))
						.extracting(ShipmentRiskTransition::getSequence, ShipmentRiskTransition::getPreviousRiskLevel,
								ShipmentRiskTransition::getRiskLevel)
						.containsExactly(org.assertj.core.groups.Tuple.tuple(1L, ShipmentRisk.Level.LOW, ShipmentRisk.Level.HIGH));
			});
		} finally {
			if (shipmentId != null) {
				transaction.executeWithoutResult(status -> removeCommittedShipment(shipmentId));
			}
		}
	}

	private void removeCommittedShipment(Long id) {
		entityManager.createNativeQuery("""
				DELETE FROM shipment_risk_alert_deliveries
				WHERE transition_id IN (SELECT id FROM shipment_risk_transitions WHERE shipment_id = :id)
				""").setParameter("id", id).executeUpdate();
		entityManager.createNativeQuery("DELETE FROM shipment_risk_transitions WHERE shipment_id = :id")
				.setParameter("id", id).executeUpdate();
		entityManager.createNativeQuery("DELETE FROM shipments WHERE id = :id")
				.setParameter("id", id).executeUpdate();
	}

	private ShipmentRiskEvaluationOutcome evaluateWhenReleased(CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		start.await();
		return evaluator.evaluate(currentShipmentId());
	}

	private Long createLowBaselineThenRaiseToHigh() {
		Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
		UserAccount user = new UserAccount("Concurrency User", "risk-concurrency-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		entityManager.persist(user);
		Shipment shipment = new Shipment(company, user, "RISK-CONCURRENT-" + UUID.randomUUID(), "Singapore", "Rotterdam", "Singapore",
				"Mother", ARRIVAL, "Feeder", ARRIVAL.plusHours(24));
		entityManager.persist(shipment);
		entityManager.flush();
		ShipmentRiskState state = new ShipmentRiskState(shipment, ShipmentRiskBaselineMode.SILENT_FIRST_VALID, Instant.now());
		state.recordValidEvaluation(new ShipmentRisk(ShipmentRisk.Level.LOW,
				"At least 24 hours to transfer cargo between vessels."), Instant.now());
		states.saveAndFlush(state);
		shipment.replaceDetails(new ShipmentDetails(shipment.getShipmentReference(), shipment.getOrigin(), shipment.getDestination(),
				shipment.getTransshipmentPort(), shipment.getMotherVessel(), ARRIVAL, shipment.getFeederVessel(), ARRIVAL.plusHours(8),
				null, null, null, null, null, null), user, Instant.now());
		entityManager.flush();
		return shipment.getId();
	}

	private Long currentShipmentId() {
		if (shipmentId == null) {
			throw new IllegalStateException("Shipment id was not initialized");
		}
		return shipmentId;
	}
}
