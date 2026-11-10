package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.UUID;

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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.RiskNotificationLevel;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

/** Offline A/B progression through the persisted scheduler and real notification eligibility path. */
@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG198_MONITORING_E2E_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG198_MONITORING_E2E_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG198_MONITORING_E2E_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG198_MONITORING_E2E_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG198_MONITORING_E2E_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG198_MONITORING_E2E_DB_PASSWORD}",
		"drift.startup-guard.expected-url=${DRIFT_CDG198_MONITORING_E2E_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG198_MONITORING_E2E_OWNERSHIP_TOKEN}",
		"app.ais.enabled=false",
		"app.risk-alerts.activation.command=false",
		"app.risk-alerts.evaluation.enabled=true",
		"app.risk-alerts.evaluation.interval=15m",
		"app.risk-alerts.mail.enabled=true",
		"app.risk-alerts.mail.dispatch-enabled=false",
		"app.risk-alerts.mail.frontend-base-url=https://app.example.test",
		"app.risk-alerts.mail.from=alerts@example.test"
})
@ActiveProfiles("test")
@ContextConfiguration(initializers = ShipmentDisposableDatabaseGuard.class)
@Import(ShipmentRiskMonitoringScenarioIntegrationTests.MailDouble.class)
class ShipmentRiskMonitoringScenarioIntegrationTests {
	private static final String REFERENCE_PREFIX = "CDG198-MONITOR-";

	@Autowired TransactionTemplate transactions;
	@Autowired CompanyRepository companies;
	@Autowired AccountRoleRepository roles;
	@Autowired UserAccountRepository users;
	@Autowired ShipmentRepository shipments;
	@Autowired ShipmentRiskStateRepository states;
	@Autowired ShipmentRiskTransitionRepository transitions;
	@Autowired ShipmentRiskAlertDeliveryRepository deliveries;
	@Autowired ShipmentRiskAlertActivationService activation;
	@Autowired ShipmentRiskAlertEvaluationProperties evaluationProperties;
	@Autowired ShipmentRiskEvaluationScheduler scheduler;
	@Autowired ShipmentRiskAlertDeliveryService deliveryService;
	@Autowired JdbcTemplate jdbc;
	@Autowired JavaMailSender mailSender;

	private Long shipmentId;
	private Long userId;
	private OffsetDateTime motherArrival;
	private String shipmentReference;
	private String userEmail;

	@BeforeEach
	void createSyntheticFixtureAndActivateMonitoring() {
		reset(mailSender);
		shipmentReference = REFERENCE_PREFIX + UUID.randomUUID();
		userEmail = "cdg198-monitor-" + UUID.randomUUID() + "@example.test";
		motherArrival = OffsetDateTime.now().plusDays(2);
		transactions.executeWithoutResult(status -> {
			Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			AccountRole role = roles.findByCode(Role.LOGISTICS_MANAGER).orElseThrow();
			UserAccount user = new UserAccount("Risk monitor test", userEmail, "hash", role, company);
			user.chooseRiskNotifications(true, RiskNotificationLevel.HIGH);
			userId = users.saveAndFlush(user).getId();
			Shipment shipment = new Shipment(company, user, shipmentReference, "Synthetic origin", "Synthetic destination",
					"Synthetic port", "Synthetic mother " + UUID.randomUUID(), motherArrival,
					"Synthetic feeder " + UUID.randomUUID(), motherArrival.plusHours(25));
			shipmentId = shipments.saveAndFlush(shipment).getId();
		});
		evaluationProperties.setEnabled(true);
		assertThat(activation.activate()).isEqualTo(ShipmentRiskAlertActivationResult.ACTIVATED);
	}

	@AfterEach
	void cleanUp() {
		if (shipmentId != null) {
			jdbc.update("DELETE FROM shipment_risk_alert_deliveries WHERE transition_id IN (SELECT id FROM shipment_risk_transitions WHERE shipment_id = ?)", shipmentId);
			jdbc.update("DELETE FROM shipment_risk_transitions WHERE shipment_id = ?", shipmentId);
			jdbc.update("DELETE FROM shipment_risk_states WHERE shipment_id = ?", shipmentId);
			jdbc.update("DELETE FROM shipments WHERE id = ?", shipmentId);
		}
		if (userId != null) jdbc.update("DELETE FROM users WHERE id = ?", userId);
	}

	@Test
	void scheduledSyntheticLowMediumHighProgressionSendsOnlyTheHighCrossingOnce() {
		assertThat(states.findById(shipmentId).orElseThrow().getLastRiskLevel()).isEqualTo(ShipmentRisk.Level.LOW);
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(shipmentId)).isEmpty();

		setConnectionWindowHours(20);
		scheduler.sweep();
		ShipmentRiskTransition medium = transitions.findByShipmentIdOrderBySequenceAsc(shipmentId).getLast();
		assertThat(medium.getPreviousRiskLevel()).isEqualTo(ShipmentRisk.Level.LOW);
		assertThat(medium.getRiskLevel()).isEqualTo(ShipmentRisk.Level.MEDIUM);
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(medium.getId())).isEmpty();

		setConnectionWindowHours(11);
		scheduler.sweep();
		ShipmentRiskTransition high = transitions.findByShipmentIdOrderBySequenceAsc(shipmentId).getLast();
		assertThat(high.getPreviousRiskLevel()).isEqualTo(ShipmentRisk.Level.MEDIUM);
		assertThat(high.getRiskLevel()).isEqualTo(ShipmentRisk.Level.HIGH);
		var eligible = deliveries.findByTransitionIdOrderByIdAsc(high.getId());
		assertThat(eligible).hasSize(1);
		assertThat(eligible.getFirst().getUser().getId()).isEqualTo(userId);

		// Repeating the unchanged polling stage must preserve the transition and queued-delivery counts.
		scheduler.sweep();
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(shipmentId)).hasSize(2);
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(high.getId())).hasSize(1);

		assertThat(deliveryService.sendDelivery(eligible.getFirst().getId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SENT);
		verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
		var sent = deliveries.findById(eligible.getFirst().getId()).orElseThrow();
		assertThat(sent.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SENT);
		assertThat(sent.getAttemptCount()).isEqualTo(1);
		verify(mailSender).send(org.mockito.ArgumentMatchers.<SimpleMailMessage>argThat(message ->
				message.getTo().length == 1 && message.getTo()[0].equals(userEmail)
						&& message.getSubject().contains("HIGH")
						&& message.getText().contains("https://app.example.test/logistics-manager/shipments/" + shipmentId)));
	}

	private void setConnectionWindowHours(int hours) {
		jdbc.update("UPDATE shipments SET planned_feeder_departure_at = ? WHERE id = ?",
				motherArrival.plusHours(hours), shipmentId);
	}

	@TestConfiguration
	static class MailDouble {
		@Bean @Primary JavaMailSender mailSender() { return mock(JavaMailSender.class); }
	}
}
