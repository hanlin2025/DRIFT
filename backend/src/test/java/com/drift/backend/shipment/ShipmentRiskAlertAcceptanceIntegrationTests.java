package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.AccountRole;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.RiskNotificationLevel;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.shipment.csvimport.ShipmentImportRow;

/** Acceptance coverage for the complete risk-transition, enrolment, and delivery path. */
@SpringBootTest(properties = {
		"app.risk-alerts.mail.enabled=true",
		"app.risk-alerts.mail.dispatch-enabled=true",
		"app.risk-alerts.mail.frontend-base-url=https://app.example.test",
		"app.risk-alerts.mail.from=alerts@example.test"
})
@ActiveProfiles("test")
@Import(ShipmentRiskAlertAcceptanceIntegrationTests.MailDouble.class)
class ShipmentRiskAlertAcceptanceIntegrationTests {

	private static final String PREFIX = "CDG199-";

	@Autowired TransactionTemplate transactions;
	@Autowired CompanyRepository companies;
	@Autowired AccountRoleRepository roles;
	@Autowired UserAccountRepository users;
	@Autowired ShipmentService shipments;
	@Autowired ShipmentRepository shipmentRepository;
	@Autowired ShipmentRiskAlertActivationService activation;
	@Autowired ShipmentRiskTransitionRepository transitions;
	@Autowired ShipmentRiskAlertDeliveryRepository deliveries;
	@Autowired ShipmentRiskAlertDeliveryService deliveryService;
	@Autowired ShipmentRiskAlertDeliveryPoller poller;
	@Autowired ShipmentRiskEvaluator evaluator;
	@Autowired LatestAisPositions aisPositions;
	@Autowired ShipmentRiskAlertMailProperties mailProperties;
	@Autowired JdbcTemplate jdbc;
	@Autowired JavaMailSender mailSender;

	private Company managingCompany;
	private UserAccount managingUser;
	private AuthenticatedUser principal;

	@BeforeEach
	void setUp() {
		reset(mailSender);
		mailProperties.setEnabled(true);
		mailProperties.setDispatchEnabled(true);
		transactions.executeWithoutResult(status -> {
			managingCompany = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			managingUser = createUser(managingCompany, Role.LOGISTICS_MANAGER, true, RiskNotificationLevel.LOW);
			principal = principal(managingUser);
		});
		activation.activate();
	}

	@AfterEach
	void cleanUp() {
		mailProperties.setEnabled(true);
		mailProperties.setDispatchEnabled(true);
		transactions.executeWithoutResult(status -> {
			jdbc.update("DELETE FROM shipment_risk_alert_deliveries WHERE transition_id IN "
					+ "(SELECT id FROM shipment_risk_transitions WHERE shipment_id IN "
					+ "(SELECT id FROM shipments WHERE shipment_reference LIKE ?))", PREFIX + "%");
			jdbc.update("DELETE FROM shipment_risk_transitions WHERE shipment_id IN "
					+ "(SELECT id FROM shipments WHERE shipment_reference LIKE ?)", PREFIX + "%");
			jdbc.update("DELETE FROM shipment_risk_states WHERE shipment_id IN "
					+ "(SELECT id FROM shipments WHERE shipment_reference LIKE ?)", PREFIX + "%");
			jdbc.update("DELETE FROM shipments WHERE shipment_reference LIKE ?", PREFIX + "%");
			jdbc.update("DELETE FROM users WHERE email LIKE ?", "cdg199-%@example.test");
			jdbc.update("DELETE FROM companies WHERE code LIKE 'CDG199_UNRELATED_%'");
		});
	}

	@Test
	void riseEnrollsManagingAndImporterUsersIndividuallyWithThresholdsAndAuditableSnapshots() {
		Company importer = companies.findByCode("STRAITS_FRESH_DEMO").orElseThrow();
		UserAccount importerUser = inTransaction(() -> createUser(importer, Role.IMPORTER, true, RiskNotificationLevel.HIGH));
		Company unrelated = unrelatedCompany();
		UserAccount unrelatedUser = inTransaction(() -> createUser(unrelated, Role.IMPORTER, true, RiskNotificationLevel.LOW));
		String motherVessel = "CDG199 Mother " + UUID.randomUUID();

		ShipmentResponse created = createImported(importer, 25, "Singapore", motherVessel);
		ShipmentRiskTransition initial = transitions.findByShipmentIdOrderBySequenceAsc(created.id()).getFirst();
		assertThat(initial.getTransitionType()).isEqualTo(ShipmentRiskTransitionType.INITIAL);
		assertThat(initial.getRiskLevel()).isEqualTo(ShipmentRisk.Level.LOW);
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(initial.getId()))
				.extracting(delivery -> delivery.getUser().getId())
				.containsExactly(managingUser.getId())
				.doesNotContain(importerUser.getId(), unrelatedUser.getId());

		ShipmentResponse medium = updateWindow(created, 20);
		ShipmentRiskTransition mediumRise = transitions.findByShipmentIdOrderBySequenceAsc(medium.id()).getLast();
		assertThat(mediumRise.getRiskLevel()).isEqualTo(ShipmentRisk.Level.MEDIUM);
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(mediumRise.getId()))
				.extracting(delivery -> delivery.getUser().getId())
				.containsExactly(managingUser.getId())
				.doesNotContain(importerUser.getId(), unrelatedUser.getId());

		ShipmentResponse updated = updateWindow(medium, 11);
		ShipmentRiskTransition highRise = transitions.findByShipmentIdOrderBySequenceAsc(updated.id()).getLast();
		assertThat(highRise.getTransitionType()).isEqualTo(ShipmentRiskTransitionType.RISE);
		assertThat(highRise.getRiskLevel()).isEqualTo(ShipmentRisk.Level.HIGH);

		List<ShipmentRiskAlertDelivery> highDeliveries = deliveries.findByTransitionIdOrderByIdAsc(highRise.getId());
		assertThat(highDeliveries).extracting(delivery -> delivery.getUser().getId())
				.containsExactlyInAnyOrder(managingUser.getId(), importerUser.getId())
				.doesNotContain(unrelatedUser.getId());

		List<SimpleMailMessage> sent = new ArrayList<>();
		doAnswer(invocation -> {
			sent.add(invocation.getArgument(0));
			return null;
		}).when(mailSender).send(any(SimpleMailMessage.class));
		for (ShipmentRiskAlertDelivery delivery : highDeliveries) {
			assertThat(deliveryService.sendDelivery(delivery.getId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SENT);
		}

		assertThat(sent).hasSize(2);
		assertThat(sent).allSatisfy(message -> {
			assertThat(message.getTo()).hasSize(1);
			assertThat(message.getSubject()).contains(updated.shipmentReference()).contains("HIGH");
			assertThat(message.getText()).contains(highRise.getExplanation()).contains("HIGH");
		});
		assertThat(sent).extracting(SimpleMailMessage::getText)
				.anySatisfy(body -> assertThat(body).contains("/logistics-manager/shipments/" + updated.id()))
				.anySatisfy(body -> assertThat(body).contains("/importer/shipments/" + updated.id()));

		aisPositions.record(new AisPosition(mmsi(), motherVessel,
				new BigDecimal("-80"), new BigDecimal("-170"), new BigDecimal("0.5"), null, null, null, true,
				null, Instant.now(), AisPositionSource.SEEDED));
		assertThat(evaluator.evaluate(updated.id())).isEqualTo(ShipmentRiskEvaluationOutcome.RISE_TRANSITION);
		ShipmentRiskTransition criticalRise = transitions.findByShipmentIdOrderBySequenceAsc(updated.id()).getLast();
		assertThat(criticalRise.getPreviousRiskLevel()).isEqualTo(ShipmentRisk.Level.HIGH);
		assertThat(criticalRise.getRiskLevel()).isEqualTo(ShipmentRisk.Level.CRITICAL);

		List<ShipmentRiskAlertDelivery> criticalDeliveries = deliveries.findByTransitionIdOrderByIdAsc(criticalRise.getId());
		assertThat(criticalDeliveries).extracting(delivery -> delivery.getUser().getId())
				.containsExactlyInAnyOrder(managingUser.getId(), importerUser.getId())
				.doesNotContain(unrelatedUser.getId());
		for (ShipmentRiskAlertDelivery delivery : criticalDeliveries) {
			assertThat(deliveryService.sendDelivery(delivery.getId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SENT);
		}
		assertThat(sent).hasSize(4);
		assertThat(sent.subList(2, 4)).allSatisfy(message -> {
			assertThat(message.getSubject()).contains(updated.shipmentReference()).contains("CRITICAL");
			assertThat(message.getText()).contains(criticalRise.getExplanation()).contains("CRITICAL");
		});
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(criticalRise.getId())).allSatisfy(delivery -> {
			assertThat(delivery.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SENT);
			assertThat(delivery.getSentAt()).isNotNull();
		});
	}

	@Test
	void unchangedAndDecreasingRiskCreateNoAlertButALaterRiseCreatesANewDelivery() {
		ShipmentResponse created = createShipment(25);
		ShipmentResponse high = updateWindow(created, 11);
		ShipmentResponse unchanged = updateWindow(high, 11);
		ShipmentResponse low = updateWindow(unchanged, 25);
		ShipmentResponse highAgain = updateWindow(low, 11);

		List<ShipmentRiskTransition> events = transitions.findByShipmentIdOrderBySequenceAsc(highAgain.id());
		assertThat(events).extracting(ShipmentRiskTransition::getTransitionType)
				.containsExactly(ShipmentRiskTransitionType.INITIAL, ShipmentRiskTransitionType.RISE, ShipmentRiskTransitionType.RISE);
		assertThat(events).extracting(ShipmentRiskTransition::getPreviousRiskLevel, ShipmentRiskTransition::getRiskLevel)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(null, ShipmentRisk.Level.LOW),
						org.assertj.core.groups.Tuple.tuple(ShipmentRisk.Level.LOW, ShipmentRisk.Level.HIGH),
						org.assertj.core.groups.Tuple.tuple(ShipmentRisk.Level.LOW, ShipmentRisk.Level.HIGH));
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(events.get(1).getId())).hasSize(1);
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(events.get(2).getId())).hasSize(1);
	}

	@Test
	void preferenceChangesAndArchivingSuppressQueuedAndRetryingDeliveriesWithoutSending() {
		ShipmentResponse preferenceShipment = createShipment(25);
		ShipmentRiskAlertDelivery preferenceDelivery = initialDelivery(preferenceShipment.id());
		doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(any(SimpleMailMessage.class));
		assertThat(deliveryService.sendDelivery(preferenceDelivery.getId()))
				.isEqualTo(ShipmentRiskAlertDeliveryResult.RETRYABLE_FAILURE);
		transactions.executeWithoutResult(status -> users.findById(managingUser.getId()).orElseThrow()
				.chooseRiskNotifications(false, RiskNotificationLevel.LOW));
		jdbc.update("UPDATE shipment_risk_alert_deliveries SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", preferenceDelivery.getId());
		reset(mailSender);
		poller.dispatchDueDeliveries();
		ShipmentRiskAlertDelivery suppressedRetry = deliveries.findById(preferenceDelivery.getId()).orElseThrow();
		assertThat(suppressedRetry.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SUPPRESSED);
		assertThat(suppressedRetry.getAttemptCount()).isEqualTo(1);

		transactions.executeWithoutResult(status -> users.findById(managingUser.getId()).orElseThrow()
				.chooseRiskNotifications(true, RiskNotificationLevel.LOW));
		ShipmentResponse archivedShipment = createShipment(25);
		ShipmentRiskAlertDelivery archivedDelivery = initialDelivery(archivedShipment.id());
		shipments.archive(principal, archivedShipment.id());
		assertThat(deliveryService.sendDelivery(archivedDelivery.getId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
		assertThat(deliveries.findById(archivedDelivery.getId()).orElseThrow().getSuppressionReason()).contains("archived");
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	@Test
	void disabledMailSuppressesARealPendingAlertWithoutAnSmtpAttempt() {
		ShipmentResponse shipment = createShipment(25);
		ShipmentRiskAlertDelivery delivery = initialDelivery(shipment.id());
		mailProperties.setEnabled(false);

		assertThat(deliveryService.sendDelivery(delivery.getId())).isEqualTo(ShipmentRiskAlertDeliveryResult.SUPPRESSED);
		ShipmentRiskAlertDelivery suppressed = deliveries.findById(delivery.getId()).orElseThrow();
		assertThat(suppressed.getStatus()).isEqualTo(ShipmentRiskAlertDeliveryStatus.SUPPRESSED);
		assertThat(suppressed.getAttemptCount()).isZero();
		assertThat(suppressed.getSuppressionReason()).contains("disabled");
		verify(mailSender, times(0)).send(any(SimpleMailMessage.class));
	}

	private ShipmentResponse createShipment(int connectionHours) {
		OffsetDateTime arrival = OffsetDateTime.now().plusDays(2);
		return shipments.create(principal, new CreateShipmentRequest(PREFIX + UUID.randomUUID(), "A", "B", "C", "Mother", arrival,
				"Feeder", arrival.plusHours(connectionHours), null, null, null, null, null, null));
	}

	private ShipmentResponse createImported(Company importer, int connectionHours, String transshipmentPort, String motherVessel) {
		String reference = PREFIX + "IMPORT-" + UUID.randomUUID();
		OffsetDateTime arrival = OffsetDateTime.now().plusDays(2);
		shipments.createImported(managingCompany, managingUser,
				new ShipmentImportRow(1, reference, "A", "B", transshipmentPort, motherVessel, arrival,
						"Feeder", arrival.plusHours(connectionHours), null), importer);
		Shipment shipment = shipmentRepository.findAll().stream()
				.filter(candidate -> reference.equals(candidate.getShipmentReference())).findFirst().orElseThrow();
		return ShipmentResponse.from(shipment, null);
	}

	private ShipmentResponse updateWindow(ShipmentResponse current, int connectionHours) {
		OffsetDateTime arrival = current.plannedMotherArrivalAt();
		return shipments.update(principal, current.id(), new UpdateShipmentRequest(current.shipmentReference(), current.origin(),
				current.destination(), current.transshipmentPort(), current.motherVessel(), arrival, current.feederVessel(),
				arrival.plusHours(connectionHours), null, null, null, null, null, null, current.version()));
	}

	private ShipmentRiskAlertDelivery initialDelivery(Long shipmentId) {
		ShipmentRiskTransition initial = transitions.findByShipmentIdOrderBySequenceAsc(shipmentId).getFirst();
		return deliveries.findByTransitionIdOrderByIdAsc(initial.getId()).stream()
				.filter(delivery -> delivery.getUser().getId().equals(managingUser.getId())).findFirst().orElseThrow();
	}

	private Company unrelatedCompany() {
		return inTransaction(() -> {
			String code = "CDG199_UNRELATED_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
			jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, true)", code, "CDG-199 unrelated");
			return companies.findByCode(code).orElseThrow();
		});
	}

	private UserAccount createUser(Company company, Role role, boolean enabled, RiskNotificationLevel minimum) {
		AccountRole accountRole = roles.findByCode(role).orElseThrow();
		UserAccount user = new UserAccount("CDG-199 " + role, "cdg199-" + UUID.randomUUID() + "@example.test", "hash", accountRole, company);
		user.chooseRiskNotifications(enabled, minimum);
		return users.saveAndFlush(user);
	}

	private static String mmsi() {
		long suffix = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 800_000_000L);
		return Long.toString(100_000_000L + suffix);
	}

	private static AuthenticatedUser principal(UserAccount user) {
		return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole(), java.time.Instant.now());
	}

	private <T> T inTransaction(java.util.function.Supplier<T> supplier) {
		return transactions.execute(status -> supplier.get());
	}

	@TestConfiguration
	static class MailDouble {
		@Bean @Primary JavaMailSender mailSender() { return mock(JavaMailSender.class); }
	}
}
