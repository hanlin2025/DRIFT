package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.transaction.TestTransaction;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.RiskNotificationLevel;
import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentRiskAlertRecipientResolverIntegrationTests {

	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-10-15T08:00:00Z");

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired ShipmentRiskEvaluator evaluator;
	@Autowired ShipmentRiskStateRepository states;
	@Autowired ShipmentRiskTransitionRepository transitions;
	@Autowired ShipmentRiskAlertDeliveryRepository deliveries;
	@Autowired ShipmentRiskAlertRecipientResolver recipients;
	@Autowired JdbcTemplate jdbc;
	@Autowired ShipmentRiskAlertActivationService activation;

	private Company managing;
	private Company importer;
	private Company unrelated;
	private AccountRole forwarderRole;

	@BeforeEach
	void setUp() {
		TestTransaction.flagForCommit();
		TestTransaction.end();
		activation.activate();
		TestTransaction.start();
		managing = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		importer = companies.findByCode("STRAITS_FRESH_DEMO").orElseThrow();
		String unrelatedCode = "UNRELATED_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, true)", unrelatedCode, "Unrelated Company");
		unrelated = companies.findByCode(unrelatedCode).orElseThrow();
		forwarderRole = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
	}

	@Test
	void enrollsManagingAndImporterUsersButNotUnrelatedUsers() {
		UserAccount manager = user(managing, true, RiskNotificationLevel.MEDIUM);
		UserAccount importerUser = user(importer, true, RiskNotificationLevel.HIGH);
		UserAccount outsider = user(unrelated, true, RiskNotificationLevel.LOW);
		ShipmentRiskTransition transition = highTransition(linkedShipment());

		assertThat(deliveries.findByTransitionIdOrderByIdAsc(transition.getId()))
				.extracting(delivery -> delivery.getUser().getId())
				.containsExactlyInAnyOrder(manager.getId(), importerUser.getId())
				.doesNotContain(outsider.getId());
		assertThat(transition.getRecipientResolutionCompletedAt()).isNotNull();
	}

	@Test
	void appliesEnabledPreferenceAndInclusiveThresholds() {
		UserAccount disabled = user(managing, false, RiskNotificationLevel.LOW);
		UserAccount below = user(managing, true, RiskNotificationLevel.CRITICAL);
		UserAccount boundary = user(managing, true, RiskNotificationLevel.HIGH);
		ShipmentRiskTransition transition = highTransition(shipment());

		assertThat(deliveries.findByTransitionIdOrderByIdAsc(transition.getId()))
				.extracting(delivery -> delivery.getUser().getId()).containsExactly(boundary.getId())
				.doesNotContain(disabled.getId(), below.getId());
	}

	@Test
	void completesZeroRecipientTransitionsAndNeverRetroactivelyEnrollsLaterOptIns() {
		UserAccount optedOut = user(managing, false, RiskNotificationLevel.LOW);
		ShipmentRiskTransition transition = highTransition(shipment());
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(transition.getId())).isEmpty();
		assertThat(transition.getRecipientResolutionCompletedAt()).isNotNull();

		inCommittedTransaction(() -> entityManager.find(UserAccount.class, optedOut.getId())
				.chooseRiskNotifications(true, RiskNotificationLevel.LOW));
		assertThat(recipients.deliveriesForCompletedTransition(transition.getId())).isEmpty();
	}

	@Test
	void failsClosedForUnexpectedCommittedIncompleteTransition() {
		Shipment shipment = shipment();
		ShipmentRiskTransition incomplete = transitions.saveAndFlush(new ShipmentRiskTransition(shipment, 1,
				ShipmentRiskTransitionType.INITIAL, null, new ShipmentRisk(ShipmentRisk.Level.HIGH, "snapshot"), Instant.now()));

		assertThatThrownBy(() -> recipients.deliveriesForCompletedTransition(incomplete.getId()))
				.isInstanceOf(IncompleteRiskTransitionRecipientResolutionException.class);
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(incomplete.getId())).isEmpty();
	}

	@AfterEach
	void cleanUpCommittedFixtures() {
		jdbc.update("DELETE FROM shipment_risk_alert_deliveries WHERE transition_id IN "
				+ "(SELECT t.id FROM shipment_risk_transitions t JOIN shipments s ON s.id = t.shipment_id "
				+ "WHERE s.shipment_reference LIKE 'RISK-RECIPIENT-%')");
		jdbc.update("DELETE FROM shipment_risk_transitions WHERE shipment_id IN "
				+ "(SELECT id FROM shipments WHERE shipment_reference LIKE 'RISK-RECIPIENT-%')");
		jdbc.update("DELETE FROM shipment_risk_states WHERE shipment_id IN "
				+ "(SELECT id FROM shipments WHERE shipment_reference LIKE 'RISK-RECIPIENT-%')");
		jdbc.update("DELETE FROM shipments WHERE shipment_reference LIKE 'RISK-RECIPIENT-%'");
		jdbc.update("DELETE FROM users WHERE email LIKE 'recipient-%@example.com'");
		jdbc.update("DELETE FROM companies WHERE code LIKE 'UNRELATED_%'");
	}

	private ShipmentRiskTransition highTransition(Shipment shipment) {
		commitCurrentTransaction();
		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.BASELINED);
		inCommittedTransaction(() -> {
			Shipment managed = entityManager.find(Shipment.class, shipment.getId());
			managed.replaceDetails(new ShipmentDetails(managed.getShipmentReference(), managed.getOrigin(), managed.getDestination(),
					managed.getTransshipmentPort(), managed.getMotherVessel(), ARRIVAL, managed.getFeederVessel(), ARRIVAL.plusHours(8),
					null, null, null, null, null, null), managed.getUpdatedBy(), Instant.now());
		});
		assertThat(evaluator.evaluate(shipment.getId())).isEqualTo(ShipmentRiskEvaluationOutcome.RISE_TRANSITION);
		return transitions.findByShipmentIdOrderBySequenceAsc(shipment.getId()).getFirst();
	}

	private Shipment linkedShipment() {
		Shipment shipment = shipment();
		shipment.linkImporter(importer);
		entityManager.flush();
		return shipment;
	}

	private Shipment shipment() {
		UserAccount creator = user(managing, false, RiskNotificationLevel.HIGH);
		Shipment shipment = new Shipment(managing, creator, "RISK-RECIPIENT-" + UUID.randomUUID(), "Singapore", "Rotterdam", "Singapore",
				"Mother", ARRIVAL, "Feeder", ARRIVAL.plusHours(24));
		entityManager.persist(shipment);
		entityManager.flush();
		states.saveAndFlush(new ShipmentRiskState(shipment, ShipmentRiskBaselineMode.SILENT_FIRST_VALID, Instant.now()));
		return shipment;
	}

	private UserAccount user(Company company, boolean enabled, RiskNotificationLevel minimum) {
		UserAccount account = new UserAccount("Recipient", "recipient-" + UUID.randomUUID() + "@example.com", "hash", forwarderRole, company);
		account.chooseRiskNotifications(enabled, minimum);
		entityManager.persist(account);
		entityManager.flush();
		return account;
	}

	private void commitCurrentTransaction() {
		entityManager.flush();
		TestTransaction.flagForCommit();
		TestTransaction.end();
	}

	private void inCommittedTransaction(Runnable operation) {
		TestTransaction.start();
		operation.run();
		entityManager.flush();
		TestTransaction.flagForCommit();
		TestTransaction.end();
	}
}
