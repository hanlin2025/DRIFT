package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

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

/** Requires a fresh, isolated V25 database: activation is intentionally durable. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentRiskAlertActivationServiceIntegrationTests {

	@Autowired ShipmentRiskAlertActivationService service;
	@Autowired ShipmentRiskAlertActivationStore activation;
	@Autowired ShipmentRiskStateRepository states;
	@Autowired ShipmentRiskTransitionRepository transitions;
	@Autowired ShipmentRiskAlertDeliveryRepository deliveries;
	@Autowired CompanyRepository companies;
	@Autowired EntityManager entityManager;

	@Test
	void firstActivationIsDurableAndIdempotent() {
		ShipmentRiskAlertActivation before = activation.lockForRiskInput();
		assertThat(before.getGeneration()).isZero();
		assertThat(before.getActivatedAt()).isNull();

		assertThat(service.activate()).isEqualTo(ShipmentRiskAlertActivationResult.ACTIVATED);
		ShipmentRiskAlertActivation active = activation.lockForRiskInput();
		assertThat(active.getGeneration()).isEqualTo(1);
		assertThat(active.getActivatedAt()).isNotNull();

		assertThat(service.activate()).isEqualTo(ShipmentRiskAlertActivationResult.ALREADY_ACTIVE);
		assertThat(activation.lockForRiskInput().getGeneration()).isEqualTo(1);
	}

	@Test
	void activationBaselinesHistoricalRiskAndSuppressesOnlyUnsentHistoricalDeliveries() {
		Fixture fixture = fixture();
		ShipmentRiskState state = states.saveAndFlush(new ShipmentRiskState(fixture.shipment,
				ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID, Instant.now()));
		ShipmentRiskTransition pendingTransition = transitions.saveAndFlush(transition(fixture, 1));
		ShipmentRiskTransition retryTransition = transitions.saveAndFlush(transition(fixture, 2));
		ShipmentRiskTransition sentTransition = transitions.saveAndFlush(transition(fixture, 3));
		ShipmentRiskAlertDelivery pending = deliveries.saveAndFlush(new ShipmentRiskAlertDelivery(pendingTransition, fixture.user, Instant.now()));
		ShipmentRiskAlertDelivery retry = deliveries.saveAndFlush(new ShipmentRiskAlertDelivery(retryTransition, fixture.secondUser, Instant.now()));
		ShipmentRiskAlertDelivery sent = deliveries.saveAndFlush(new ShipmentRiskAlertDelivery(sentTransition, fixture.thirdUser, Instant.now()));
		UUID retryClaim = UUID.randomUUID();
		retry.claim(retryClaim, Instant.now().plusSeconds(60), Instant.now()); retry.recordAttempt(retryClaim, Instant.now());
		retry.markFailure(retryClaim, ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE, "historical", Instant.now().plusSeconds(60), Instant.now());
		UUID sentClaim = UUID.randomUUID();
		sent.claim(sentClaim, Instant.now().plusSeconds(60), Instant.now()); sent.recordAttempt(sentClaim, Instant.now()); sent.markSent(sentClaim, Instant.now());
		deliveries.flush();

		assertThat(service.activate()).isEqualTo(ShipmentRiskAlertActivationResult.ACTIVATED);
		entityManager.flush();
		entityManager.clear();
		ShipmentRiskState activatedState = states.findById(fixture.shipment.getId()).orElseThrow();
		assertThat(activatedState.getActivationGeneration()).isEqualTo(1);
		assertThat(activatedState.getLastRiskLevel()).isNotNull();
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(fixture.shipment.getId())).hasSize(3);
		assertThat(deliveries.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SUPPRESSED);
		assertThat(deliveries.findById(retry.getId()).orElseThrow().getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SUPPRESSED);
		assertThat(deliveries.findById(sent.getId()).orElseThrow().getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SENT);
	}

	@Test
	void activationRejectsALiveHistoricalClaimAndRollsBackTheSingleton() {
		Fixture fixture = fixture();
		ShipmentRiskTransition transition = transitions.saveAndFlush(transition(fixture, 1));
		ShipmentRiskAlertDelivery delivery = deliveries.saveAndFlush(new ShipmentRiskAlertDelivery(transition, fixture.user, Instant.now()));
		delivery.claim(UUID.randomUUID(), Instant.now().plusSeconds(60), Instant.now());
		deliveries.flush();

		assertThatThrownBy(() -> service.activate()).isInstanceOf(IllegalStateException.class);
		assertThat(activation.lockForRiskInput().getGeneration()).isZero();
		assertThat(activation.lockForRiskInput().getActivatedAt()).isNull();
	}

	private ShipmentRiskTransition transition(Fixture fixture, long sequence) {
		return new ShipmentRiskTransition(fixture.shipment, sequence, ShipmentRiskTransitionType.INITIAL, null,
				new ShipmentRisk(ShipmentRisk.Level.HIGH, "historical " + sequence), Instant.now());
	}

	private Fixture fixture() {
		Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
		UserAccount first = account(role, company); UserAccount second = account(role, company); UserAccount third = account(role, company);
		Shipment shipment = new Shipment(company, first, "CDG198-ACT-" + UUID.randomUUID(), "A", "B", "C", "Mother",
				OffsetDateTime.now().plusHours(1), "Feeder", OffsetDateTime.now().plusHours(25));
		entityManager.persist(shipment); entityManager.flush();
		return new Fixture(shipment, first, second, third);
	}

	private UserAccount account(AccountRole role, Company company) {
		UserAccount account = new UserAccount("Activation", "activation-" + UUID.randomUUID() + "@example.test", "hash", role, company);
		entityManager.persist(account); return account;
	}

	private record Fixture(Shipment shipment, UserAccount user, UserAccount secondUser, UserAccount thirdUser) { }
}
