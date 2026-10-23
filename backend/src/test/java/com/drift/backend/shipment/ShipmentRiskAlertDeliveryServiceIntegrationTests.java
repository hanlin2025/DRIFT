package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.RiskNotificationLevel;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest(properties = {
		"app.risk-alerts.mail.enabled=true",
		"app.risk-alerts.mail.dispatch-enabled=true",
		"app.risk-alerts.mail.frontend-base-url=https://app.example.test",
		"app.risk-alerts.mail.from=alerts@example.test",
		"app.risk-alerts.mail.claim-lease=1m"
})
@ActiveProfiles("test")
@Import(ShipmentRiskAlertDeliveryServiceIntegrationTests.MailDouble.class)
class ShipmentRiskAlertDeliveryServiceIntegrationTests {

	private static final String PREFIX = "CDG196-";

	@Autowired TransactionTemplate transactions;
	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired ShipmentRiskAlertDeliveryRepository deliveries;
	@Autowired ShipmentRiskAlertDeliveryService service;
	@Autowired ShipmentRiskAlertDeliveryPoller poller;
	@Autowired ShipmentRiskAlertDeliveryStore store;
	@Autowired ShipmentRiskStateRepository states;
	@Autowired ShipmentRiskAlertActivationService activationService;
	@Autowired ShipmentRiskAlertActivationStore activationStore;
	@Autowired ShipmentRiskAlertMailProperties mailProperties;
	@Autowired JavaMailSender mailSender;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void resetMail() {
		reset(mailSender);
		mailProperties.setEnabled(true);
		mailProperties.setDispatchEnabled(true);
		transactions.executeWithoutResult(status -> jdbc.update("UPDATE companies SET active = true WHERE code = 'HARBOURLINE_DEMO'"));
		activationService.activate();
	}

	@AfterEach
	void cleanUp() {
		mailProperties.setEnabled(true);
		mailProperties.setDispatchEnabled(true);
		transactions.executeWithoutResult(status -> {
			jdbc.update("""
					DELETE FROM shipment_risk_alert_deliveries d USING users u
					WHERE d.user_id = u.id AND u.email LIKE ?
					""", PREFIX.toLowerCase() + "%@example.test");
			jdbc.update("DELETE FROM shipment_risk_transitions WHERE shipment_id IN (SELECT id FROM shipments WHERE shipment_reference LIKE ?)", PREFIX + "%");
			jdbc.update("DELETE FROM shipments WHERE shipment_reference LIKE ?", PREFIX + "%");
			jdbc.update("DELETE FROM users WHERE email LIKE ?", PREFIX.toLowerCase() + "%@example.test");
			jdbc.update("DELETE FROM companies WHERE code LIKE 'CDG196_VIS_%'");
		});
	}

	@Test
	void sendsAnEligibleDeliveryAndRecordsSubmissionAuditWithRoleSpecificLink() {
		DeliveryFixture fixture = createDelivery(Role.IMPORTER, RiskNotificationLevel.MEDIUM, "Stored explanation: schedule changed.");
		SimpleMailMessage[] sent = new SimpleMailMessage[1];
		doAnswer(invocation -> { sent[0] = invocation.getArgument(0); return null; }).when(mailSender).send(any(SimpleMailMessage.class));

		assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SENT);
		ShipmentRiskAlertDelivery delivery = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertThat(delivery.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SENT);
		assertThat(delivery.getAttemptCount()).isEqualTo(1);
		assertThat(delivery.getSentAt()).isNotNull();
		assertThat(delivery.getClaimToken()).isNull();
		assertThat(sent[0].getSubject()).contains(fixture.reference()).contains("HIGH");
		assertThat(sent[0].getText()).contains("Stored explanation: schedule changed.")
				.contains("https://app.example.test/importer/shipments/" + fixture.shipmentId())
				.contains(fixture.reference());
	}

	@Test
	void suppressesWithoutAnAttemptWhenPreferenceWasDisabledBeforeSend() {
		DeliveryFixture fixture = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "snapshot");
		transactions.executeWithoutResult(status -> {
			UserAccount user = entityManager.find(UserAccount.class, fixture.userId());
			user.chooseRiskNotifications(false, RiskNotificationLevel.LOW);
		});

		assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
		ShipmentRiskAlertDelivery delivery = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertThat(delivery.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SUPPRESSED);
		assertThat(delivery.getAttemptCount()).isZero();
		assertThat(delivery.getSuppressionReason()).contains("preference");
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	@Test
	void suppressesWhenMailIsDisabledWithoutCallingSmtp() {
		DeliveryFixture fixture = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "snapshot");
		mailProperties.setEnabled(false);

		assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
		ShipmentRiskAlertDelivery delivery = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertThat(delivery.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SUPPRESSED);
		assertThat(delivery.getAttemptCount()).isZero();
		assertThat(delivery.getSuppressionReason()).contains("disabled");
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	@Test
	void suppressesWhenTheShipmentRiskHasFallenBelowTheQueuedTransition() {
		DeliveryFixture fixture = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "snapshot");
		transactions.executeWithoutResult(status -> {
			Shipment shipment = entityManager.find(Shipment.class, fixture.shipmentId());
			long generation = activationStore.lockForRiskInput().getGeneration();
			ShipmentRiskState state = new ShipmentRiskState(shipment, ShipmentRiskBaselineMode.SILENT_FIRST_VALID, generation, Instant.now());
			state.recordValidEvaluation(new ShipmentRisk(ShipmentRisk.Level.LOW, "Current risk is low"), Instant.now());
			states.save(state);
		});

		assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
		ShipmentRiskAlertDelivery delivery = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertThat(delivery.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SUPPRESSED);
		assertThat(delivery.getAttemptCount()).isZero();
		assertThat(delivery.getSuppressionReason()).contains("risk has fallen");
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	@Test
	void doesNotClaimPendingDeliveriesWhenDispatchIsDisabled() {
		DeliveryFixture fixture = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "snapshot");
		mailProperties.setDispatchEnabled(false);

		poller.dispatchDueDeliveries();

		ShipmentRiskAlertDelivery delivery = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertThat(delivery.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.PENDING);
		assertThat(delivery.getAttemptCount()).isZero();
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	@Test
	void suppressesWhenTheCurrentAccountCompanyVisibilityOrThresholdIsNoLongerEligible() {
		DeliveryFixture removed = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "removed");
		DeliveryFixture raisedThreshold = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "threshold");
		DeliveryFixture archived = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "archived");
		transactions.executeWithoutResult(status -> {
			jdbc.update("UPDATE users SET status = 'REMOVED' WHERE id = ?", removed.userId());
			UserAccount user = entityManager.find(UserAccount.class, raisedThreshold.userId());
			user.chooseRiskNotifications(true, RiskNotificationLevel.CRITICAL);
			jdbc.update("UPDATE shipments SET status = 'ARCHIVED' WHERE id = ?", archived.shipmentId());
		});

		assertThat(service.sendDelivery(removed.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
		assertThat(service.sendDelivery(raisedThreshold.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
		assertThat(service.sendDelivery(archived.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
		assertThat(deliveries.findById(removed.deliveryId()).orElseThrow().getSuppressionReason()).contains("account");
		assertThat(deliveries.findById(raisedThreshold.deliveryId()).orElseThrow().getSuppressionReason()).contains("preference");
		assertThat(deliveries.findById(archived.deliveryId()).orElseThrow().getSuppressionReason()).contains("archived");
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	@Test
	void suppressesWhenTheRecipientCompanyIsInactiveOrNoLongerVisible() {
		DeliveryFixture inactiveCompany = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "inactive-company");
		transactions.executeWithoutResult(status -> jdbc.update("UPDATE companies SET active = false WHERE id = (SELECT company_id FROM users WHERE id = ?)",
				inactiveCompany.userId()));
		try {
			assertThat(service.sendDelivery(inactiveCompany.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
			assertThat(deliveries.findById(inactiveCompany.deliveryId()).orElseThrow().getSuppressionReason()).contains("company");
		} finally {
			transactions.executeWithoutResult(status -> jdbc.update("UPDATE companies SET active = true WHERE code = 'HARBOURLINE_DEMO'"));
		}

		DeliveryFixture lostVisibility = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "lost-visibility");
		transactions.executeWithoutResult(status -> {
			String code = "CDG196_VIS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
			jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, true)", code, "CDG196 visibility test");
			Long unrelatedCompanyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
			jdbc.update("UPDATE users SET company_id = ? WHERE id = ?", unrelatedCompanyId, lostVisibility.userId());
		});
		assertThat(service.sendDelivery(lostVisibility.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
		assertThat(deliveries.findById(lostVisibility.deliveryId()).orElseThrow().getSuppressionReason()).contains("access");
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	@Test
	void recordsFirstSmtpFailureForOneMinuteRetryWithoutSendingAgain() {
		DeliveryFixture fixture = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "snapshot");
		Instant before = Instant.now();
		doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));

		assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.RETRYABLE_FAILURE);
		ShipmentRiskAlertDelivery delivery = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertThat(delivery.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE);
		assertThat(delivery.getAttemptCount()).isEqualTo(1);
		assertThat(delivery.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(59));
		assertThat(delivery.getNextAttemptAt()).isBefore(before.plusSeconds(62));
		assertThat(delivery.getLastFailure()).contains("MailSendException");
		assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.NOT_PENDING);
		verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
	}

	@Test
	void pollsDueRetriesAndRechecksPreferencesBeforeSending() {
		DeliveryFixture fixture = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "retry snapshot");
		doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));
		assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.RETRYABLE_FAILURE);
		jdbc.update("UPDATE shipment_risk_alert_deliveries SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", fixture.deliveryId());
		reset(mailSender);
		poller.dispatchDueDeliveries();
		assertThat(deliveries.findById(fixture.deliveryId()).orElseThrow().getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SENT);
		assertThat(deliveries.findById(fixture.deliveryId()).orElseThrow().getAttemptCount()).isEqualTo(2);
		verify(mailSender, times(1)).send(any(SimpleMailMessage.class));

		DeliveryFixture preferenceChanged = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "suppressed retry");
		doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));
		assertThat(service.sendDelivery(preferenceChanged.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.RETRYABLE_FAILURE);
		transactions.executeWithoutResult(status -> entityManager.find(UserAccount.class, preferenceChanged.userId())
				.chooseRiskNotifications(false, RiskNotificationLevel.LOW));
		jdbc.update("UPDATE shipment_risk_alert_deliveries SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", preferenceChanged.deliveryId());
		reset(mailSender);
		poller.dispatchDueDeliveries();
		ShipmentRiskAlertDelivery suppressed = deliveries.findById(preferenceChanged.deliveryId()).orElseThrow();
		assertThat(suppressed.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SUPPRESSED);
		assertThat(suppressed.getAttemptCount()).isEqualTo(1);
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	@Test
	void doesNotPollARetryBeforeItsPersistedDueTime() {
		DeliveryFixture fixture = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "future retry");
		doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));
		assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.RETRYABLE_FAILURE);
		reset(mailSender);

		poller.dispatchDueDeliveries();
		ShipmentRiskAlertDelivery delivery = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertThat(delivery.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE);
		assertThat(delivery.getAttemptCount()).isEqualTo(1);
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	@Test
	void stopsAfterTheFourthFailedAttemptAndRecoversAnExpiredClaimWithoutResettingAttempts() {
		DeliveryFixture fixture = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "bounded retry");
		doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(
					attempt == 4 ? ShipmentRiskAlertDeliveryResult.EXHAUSTED : ShipmentRiskAlertDeliveryResult.RETRYABLE_FAILURE);
			if (attempt < 4) {
				jdbc.update("UPDATE shipment_risk_alert_deliveries SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", fixture.deliveryId());
			}
		}
		ShipmentRiskAlertDelivery exhausted = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertThat(exhausted.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.EXHAUSTED);
		assertThat(exhausted.getAttemptCount()).isEqualTo(4);
		assertThat(exhausted.getNextAttemptAt()).isNull();
		assertThat(service.sendDelivery(fixture.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.NOT_PENDING);
		verify(mailSender, times(4)).send(any(SimpleMailMessage.class));

		DeliveryFixture abandoned = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "abandoned claim");
		ShipmentRiskAlertDeliveryStore.ClaimResult claim = store.claimDueDelivery(abandoned.deliveryId(), true);
		assertThat(claim.kind()).isEqualTo(ShipmentRiskAlertDeliveryStore.ClaimResult.Kind.CLAIMED);
		jdbc.update("UPDATE shipment_risk_alert_deliveries SET claim_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", abandoned.deliveryId());
		reset(mailSender);
		poller.dispatchDueDeliveries();
		ShipmentRiskAlertDelivery recovered = deliveries.findById(abandoned.deliveryId()).orElseThrow();
		assertThat(recovered.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SENT);
		assertThat(recovered.getAttemptCount()).isEqualTo(2);
	}

	@Test
	void aFailedRecipientDoesNotBlockAnotherPendingDelivery() {
		DeliveryFixture failing = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "first");
		DeliveryFixture succeeding = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "second");
		doAnswer(invocation -> {
			SimpleMailMessage message = invocation.getArgument(0);
			if (message.getTo()[0].contains(failing.email())) {
				throw new MailSendException("smtp unavailable");
			}
			return null;
		}).when(mailSender).send(any(SimpleMailMessage.class));

		assertThat(service.sendDelivery(failing.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.RETRYABLE_FAILURE);
		assertThat(service.sendDelivery(succeeding.deliveryId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SENT);
		assertThat(deliveries.findById(failing.deliveryId()).orElseThrow().getStatus())
				.isEqualTo(ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE);
		assertThat(deliveries.findById(succeeding.deliveryId()).orElseThrow().getStatus())
				.isEqualTo(ShipmentRiskAlertDeliveryStatus.SENT);
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(succeeding.transitionId())).hasSize(1);
	}

	@Test
	void rejectsAStaleClaimTokenAndAllowsOnlyOneConcurrentSmtpSubmission() throws Exception {
		DeliveryFixture fixture = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "snapshot");
		ShipmentRiskAlertDeliveryStore.ClaimResult claim = store.claimDueDelivery(fixture.deliveryId(), true);
		assertThat(claim.kind()).isEqualTo(ShipmentRiskAlertDeliveryStore.ClaimResult.Kind.CLAIMED);
		assertThat(store.markSent(fixture.deliveryId(), UUID.randomUUID())).isFalse();
		assertThat(store.markSent(fixture.deliveryId(), claim.token())).isTrue();

		DeliveryFixture concurrent = createDelivery(Role.FREIGHT_FORWARDER, RiskNotificationLevel.LOW, "concurrent");
		CountDownLatch enteredSend = new CountDownLatch(1);
		CountDownLatch releaseSend = new CountDownLatch(1);
		doAnswer(invocation -> {
			enteredSend.countDown();
			assertThat(releaseSend.await(10, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(mailSender).send(any(SimpleMailMessage.class));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<ShipmentRiskAlertDeliveryResult> first = executor.submit(() -> service.sendDelivery(concurrent.deliveryId()));
			assertThat(enteredSend.await(10, TimeUnit.SECONDS)).isTrue();
			Future<ShipmentRiskAlertDeliveryResult> second = executor.submit(() -> service.sendDelivery(concurrent.deliveryId()));
			assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(ShipmentRiskAlertDeliveryResult.NOT_PENDING);
			releaseSend.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(ShipmentRiskAlertDeliveryResult.SENT);
		} finally {
			releaseSend.countDown();
			executor.shutdownNow();
		}
		verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
	}

	private DeliveryFixture createDelivery(Role role, RiskNotificationLevel minimum, String explanation) {
		return transactions.execute(status -> {
			long generation = activationStore.lockForRiskInput().getGeneration();
			Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			AccountRole accountRole = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
					.setParameter("code", role).getSingleResult();
			String suffix = UUID.randomUUID().toString();
			String email = (PREFIX + suffix + "@example.test").toLowerCase();
			UserAccount user = new UserAccount("Risk Recipient", email, "hash", accountRole, company);
			user.chooseRiskNotifications(true, minimum);
			entityManager.persist(user);
			Shipment shipment = new Shipment(company, user, PREFIX + suffix, "Singapore", "Rotterdam", "Singapore", "Mother",
					OffsetDateTime.parse("2026-10-15T08:00:00Z"), "Feeder", OffsetDateTime.parse("2026-10-16T08:00:00Z"));
			entityManager.persist(shipment);
			ShipmentRiskTransition transition = new ShipmentRiskTransition(shipment, 1, generation, ShipmentRiskTransitionType.INITIAL,
					null, new ShipmentRisk(ShipmentRisk.Level.HIGH, explanation), Instant.now());
			entityManager.persist(transition);
			ShipmentRiskAlertDelivery delivery = new ShipmentRiskAlertDelivery(transition, user, Instant.now());
			entityManager.persist(delivery);
			entityManager.flush();
			assertThat(jdbc.queryForObject("SELECT activation_generation FROM shipment_risk_transitions WHERE id = ?", Long.class,
					transition.getId())).isEqualTo(generation);
			return new DeliveryFixture(delivery.getId(), transition.getId(), shipment.getId(), user.getId(), shipment.getShipmentReference(), email);
		});
	}

	private record DeliveryFixture(Long deliveryId, Long transitionId, Long shipmentId, Long userId, String reference, String email) { }

	@TestConfiguration
	static class MailDouble {
		@Bean
		@Primary
		JavaMailSender riskAlertMailSender() {
			return mock(JavaMailSender.class);
		}
	}
}
