package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentRiskAlertPersistenceIntegrationTests {

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired ShipmentRiskStateRepository states;
	@Autowired ShipmentRiskStateStore stateStore;
	@Autowired ShipmentRiskTransitionRepository transitions;
	@Autowired ShipmentRiskAlertDeliveryRepository deliveries;
	@Autowired JdbcTemplate jdbc;

	private Shipment shipment;
	private UserAccount user;
	private UserAccount secondUser;

	@BeforeEach
	void setUp() {
		Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
		user = new UserAccount("Risk User", "risk-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		secondUser = new UserAccount("Risk User Two", "risk-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		entityManager.persist(user);
		entityManager.persist(secondUser);
		shipment = new Shipment(company, user, "RISK-" + UUID.randomUUID(), "Singapore", "Rotterdam", "Singapore",
				"Mother", OffsetDateTime.parse("2026-10-15T08:00:00Z"), "Feeder",
				OffsetDateTime.parse("2026-10-16T08:00:00Z"));
		entityManager.persist(shipment);
		entityManager.flush();
	}

	@Test
	void statePersistsLastValidRiskAndLeavesItUnchangedForNullRisk() {
		Instant firstTime = Instant.parse("2026-10-01T00:00:00Z");
		ShipmentRiskState state = states.saveAndFlush(new ShipmentRiskState(shipment,
				ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID, firstTime));
		assertThat(state.recordValidEvaluation(new ShipmentRisk(ShipmentRisk.Level.HIGH, "initial explanation"), firstTime)).isTrue();
		states.flush();

		assertThat(state.recordValidEvaluation(null, firstTime.plusSeconds(60))).isFalse();
		states.flush();
		entityManager.clear();

		ShipmentRiskState reloaded = states.findById(shipment.getId()).orElseThrow();
		assertThat(reloaded.getLastRiskLevel()).isEqualTo(ShipmentRisk.Level.HIGH);
		assertThat(reloaded.getLastExplanation()).isEqualTo("initial explanation");
		assertThat(reloaded.getEvaluatedAt()).isEqualTo(firstTime);
		assertThat(reloaded.getBaselineMode()).isEqualTo(ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID);
		assertThat(reloaded.getLastTransitionSequence()).isZero();
	}

	@Test
	void stateRowLockAndSilentMissingStateRecoveryDoNotInferANewShipment() {
		ShipmentRiskState recovered = stateStore.lockOrInitializeSilently(shipment.getId());
		assertThat(recovered.getBaselineMode()).isEqualTo(ShipmentRiskBaselineMode.SILENT_FIRST_VALID);
		assertThat(recovered.getLastRiskLevel()).isNull();
	}

	@Test
	void initialAndRiseTransitionsAllowNullAndNonNullPreviousLevelsAndRepeatedSeverity() {
		Instant now = Instant.parse("2026-10-01T00:00:00Z");
		states.saveAndFlush(new ShipmentRiskState(shipment, ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID, now));
		ShipmentRiskState locked = states.findByShipmentIdForUpdate(shipment.getId()).orElseThrow();
		ShipmentRiskTransition initial = transitions.saveAndFlush(new ShipmentRiskTransition(shipment,
				locked.allocateTransitionSequence(),
				ShipmentRiskTransitionType.INITIAL, null,
				new ShipmentRisk(ShipmentRisk.Level.HIGH, "snapshot"), now));
		ShipmentRiskTransition rise = transitions.saveAndFlush(new ShipmentRiskTransition(shipment,
				locked.allocateTransitionSequence(),
				ShipmentRiskTransitionType.RISE, ShipmentRisk.Level.LOW,
				new ShipmentRisk(ShipmentRisk.Level.HIGH, "snapshot again"), now.plusSeconds(60)));
		states.flush();

		assertThat(initial.getPreviousRiskLevel()).isNull();
		assertThat(rise.getPreviousRiskLevel()).isEqualTo(ShipmentRisk.Level.LOW);
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(shipment.getId()))
				.extracting(ShipmentRiskTransition::getRiskLevel)
				.containsExactly(ShipmentRisk.Level.HIGH, ShipmentRisk.Level.HIGH);
		assertThat(locked.getLastTransitionSequence()).isEqualTo(2);
		assertThatThrownBy(() -> transitions.saveAndFlush(new ShipmentRiskTransition(shipment, 2,
				ShipmentRiskTransitionType.RISE, ShipmentRisk.Level.MEDIUM,
				new ShipmentRisk(ShipmentRisk.Level.CRITICAL, "duplicate sequence"), now)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void foreignKeysRejectTransitionsForMissingShipments() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO shipment_risk_transitions
					(shipment_id, sequence, transition_type, risk_level, explanation, occurred_at, created_at)
				VALUES (?, 1, 'INITIAL', 'HIGH', 'test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", Long.MAX_VALUE))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deliveryRecordsArePerUserAndFencedByExpiringClaimTokens() {
		Instant now = Instant.parse("2026-10-01T00:00:00Z");
		ShipmentRiskTransition transition = transitions.saveAndFlush(new ShipmentRiskTransition(shipment, 1,
				ShipmentRiskTransitionType.INITIAL, null,
				new ShipmentRisk(ShipmentRisk.Level.HIGH, "event explanation"), now));
		ShipmentRiskAlertDelivery first = deliveries.saveAndFlush(new ShipmentRiskAlertDelivery(transition, user, now));
		ShipmentRiskAlertDelivery second = deliveries.saveAndFlush(new ShipmentRiskAlertDelivery(transition, secondUser, now));
		UUID firstClaim = UUID.randomUUID();
		assertThat(first.claim(firstClaim, now.plusSeconds(30), now)).isTrue();
		assertThat(first.recordAttempt(firstClaim, now.plusSeconds(1))).isTrue();
		assertThat(first.markFailure(firstClaim, ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE,
				"sanitized connection failure", now.plusSeconds(60), now.plusSeconds(2))).isTrue();
		UUID retryClaim = UUID.randomUUID();
		assertThat(first.claim(retryClaim, now.plusSeconds(90), now.plusSeconds(60))).isTrue();
		assertThat(first.recordAttempt(firstClaim, now.plusSeconds(61))).isFalse();
		assertThat(first.recordAttempt(retryClaim, now.plusSeconds(61))).isTrue();
		assertThat(first.markSent(retryClaim, now.plusSeconds(62))).isTrue();
		deliveries.flush();

		assertThat(first.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SENT);
		assertThat(first.getAttemptCount()).isEqualTo(2);
		assertThat(first.getSentAt()).isEqualTo(now.plusSeconds(62));
		assertThat(first.getNextAttemptAt()).isNull();
		assertThat(second.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.PENDING);
		assertThatThrownBy(() -> deliveries.saveAndFlush(new ShipmentRiskAlertDelivery(transition, user, now)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void suppressionAndExhaustionMetadataArePersistable() {
		Instant now = Instant.parse("2026-10-01T00:00:00Z");
		ShipmentRiskTransition transition = transitions.saveAndFlush(new ShipmentRiskTransition(shipment, 1,
				ShipmentRiskTransitionType.INITIAL, null,
				new ShipmentRisk(ShipmentRisk.Level.HIGH, "event explanation"), now));
		ShipmentRiskAlertDelivery delivery = deliveries.saveAndFlush(new ShipmentRiskAlertDelivery(transition, user, now));
		UUID token = UUID.randomUUID();
		assertThat(delivery.claim(token, now.plusSeconds(30), now)).isTrue();
		assertThat(delivery.recordAttempt(token, now.plusSeconds(1))).isTrue();
		assertThat(delivery.markFailure(token, ShipmentRiskAlertDeliveryStatus.EXHAUSTED,
				"sanitized terminal failure", null, now.plusSeconds(2))).isTrue();
		assertThat(delivery.getLastFailureAt()).isEqualTo(now.plusSeconds(2));
		assertThat(delivery.getLastFailure()).isEqualTo("sanitized terminal failure");
		assertThat(delivery.getNextAttemptAt()).isNull();
	}
}
