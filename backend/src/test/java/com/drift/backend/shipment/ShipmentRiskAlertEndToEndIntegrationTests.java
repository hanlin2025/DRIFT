package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.RiskNotificationLevel;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

@SpringBootTest(properties = {
		"app.risk-alerts.mail.enabled=true",
		"app.risk-alerts.mail.frontend-base-url=https://app.example.test",
		"app.risk-alerts.mail.from=alerts@example.test"
})
@ActiveProfiles("test")
@Import(ShipmentRiskAlertEndToEndIntegrationTests.MailDouble.class)
class ShipmentRiskAlertEndToEndIntegrationTests {
	@Autowired TransactionTemplate transactions;
	@Autowired CompanyRepository companies;
	@Autowired AccountRoleRepository roles;
	@Autowired UserAccountRepository users;
	@Autowired ShipmentService shipments;
	@Autowired ShipmentRiskAlertActivationService activation;
	@Autowired ShipmentRiskTransitionRepository transitions;
	@Autowired ShipmentRiskAlertDeliveryRepository deliveries;
	@Autowired ShipmentRiskAlertDeliveryService deliveryService;
	@Autowired JavaMailSender mailSender;

	private AuthenticatedUser principal;

	@BeforeEach
	void activateAndCreateEligibleUser() {
		reset(mailSender);
		transactions.executeWithoutResult(status -> {
			Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			AccountRole role = roles.findByCode(Role.LOGISTICS_MANAGER).orElseThrow();
			UserAccount user = new UserAccount("End to end", "e2e-" + UUID.randomUUID() + "@example.test", "hash", role, company);
			user.chooseRiskNotifications(true, RiskNotificationLevel.LOW);
			users.save(user);
			principal = new AuthenticatedUser(user.getId(), user.getEmail(), Role.LOGISTICS_MANAGER, java.time.Instant.now());
		});
		activation.activate();
	}

	@Test
	void sendsAnInitialAlertCreatedByTheRealAfterCommitWorkflow() {
		OffsetDateTime arrival = OffsetDateTime.now().plusDays(2);
		ShipmentResponse shipment = shipments.create(principal, new CreateShipmentRequest("CDG198-E2E-" + UUID.randomUUID(),
				"A", "B", "C", "Mother", arrival, "Feeder", arrival.plusHours(25), null, null, null, null, null, null));
		var persistedTransitions = transitions.findByShipmentIdOrderBySequenceAsc(shipment.id());
		assertThat(persistedTransitions).hasSize(1);
		ShipmentRiskTransition transition = persistedTransitions.getFirst();
		assertThat(transition.getTransitionType()).isEqualTo(ShipmentRiskTransitionType.INITIAL);
		ShipmentRiskAlertDelivery delivery = deliveries.findByTransitionIdOrderByIdAsc(transition.getId()).stream()
				.filter(candidate -> candidate.getUser().getId().equals(principal.id())).findFirst().orElseThrow();
		assertThat(delivery.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.PENDING);

		reset(mailSender);
		assertThat(deliveryService.sendDelivery(delivery.getId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SENT);
		verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
		ShipmentRiskAlertDelivery sent = deliveries.findById(delivery.getId()).orElseThrow();
		assertThat(sent.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SENT);
		assertThat(sent.getAttemptCount()).isEqualTo(1);
		assertThat(sent.getSentAt()).isNotNull();
	}

	@TestConfiguration
	static class MailDouble {
		@Bean @Primary JavaMailSender mailSender() { return mock(JavaMailSender.class); }
	}
}
