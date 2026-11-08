package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

@SpringBootTest
@ActiveProfiles("test")
class ShipmentRiskEvaluationSchedulerIntegrationTests {
	@Autowired TransactionTemplate transactions;
	@Autowired CompanyRepository companies;
	@Autowired AccountRoleRepository roles;
	@Autowired UserAccountRepository users;
	@Autowired ShipmentRepository shipments;
	@Autowired ShipmentRiskStateRepository states;
	@Autowired ShipmentRiskAlertActivationService activation;
	@Autowired ShipmentRiskAlertEvaluationProperties properties;
	@Autowired ShipmentRiskEvaluationScheduler scheduler;
	@Autowired JdbcTemplate jdbc;

	private Company company;
	private UserAccount creator;
	private boolean originalEnabled;
	private int originalBatchSize;

	@BeforeEach
	void activateRiskAlerts() {
		cleanupTestData();
		originalEnabled = properties.isEnabled();
		originalBatchSize = properties.getBatchSize();
		properties.setEnabled(false);
		properties.setBatchSize(1);
		transactions.executeWithoutResult(status -> {
			company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			AccountRole role = roles.findByCode(Role.FREIGHT_FORWARDER).orElseThrow();
			creator = users.save(new UserAccount("Sweep creator", "sweep-" + UUID.randomUUID() + "@example.test",
					"hash", role, company));
		});
		activation.activate();
	}

	@AfterEach
	void cleanUp() {
		cleanupTestData();
		properties.setEnabled(originalEnabled);
		properties.setBatchSize(originalBatchSize);
	}

	@Test
	void disabledSweepDoesNotEvaluateShipments() {
		Long shipmentId = createShipment(false);
		scheduler.sweep();
		assertThat(states.findById(shipmentId).orElseThrow().getLastRiskLevel()).isNull();
	}

	@Test
	void enabledSweepPagesActiveShipmentsAndSkipsArchivedShipments() {
		Long firstActive = createShipment(false);
		Long secondActive = createShipment(false);
		Long archived = createShipment(true);
		properties.setEnabled(true);

		scheduler.sweep();

		assertThat(states.findById(firstActive).orElseThrow().getLastRiskLevel()).isNotNull();
		assertThat(states.findById(secondActive).orElseThrow().getLastRiskLevel()).isNotNull();
		assertThat(states.findById(archived).orElseThrow().getLastRiskLevel()).isNull();
	}

	@Test
	void sweepRecoversAnAlertableShipmentThatMissedAfterCommitEvaluationWithoutDuplicates() {
		Long shipmentId = createShipment(false, ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID);
		properties.setEnabled(true);

		scheduler.sweep();
		assertThat(states.findById(shipmentId).orElseThrow().getLastRiskLevel()).isNotNull();
		scheduler.sweep();

		assertThat(states.findById(shipmentId).orElseThrow().getLastTransitionSequence()).isEqualTo(1);
	}

	private Long createShipment(boolean archived) {
		return createShipment(archived, ShipmentRiskBaselineMode.SILENT_FIRST_VALID);
	}

	private Long createShipment(boolean archived, ShipmentRiskBaselineMode baselineMode) {
		return transactions.execute(status -> {
			OffsetDateTime arrival = OffsetDateTime.now().plusDays(2);
			Shipment shipment = shipments.saveAndFlush(new Shipment(company, creator, "CDG198-SWEEP-" + UUID.randomUUID(),
					"A", "B", "C", "Mother", arrival, "Feeder", arrival.plusHours(25)));
			if (archived) shipment.archive();
			states.saveAndFlush(new ShipmentRiskState(shipment, baselineMode, 1, Instant.now()));
			return shipment.getId();
		});
	}

	private void cleanupTestData() {
		String shipmentPattern = "CDG198-SWEEP-%";
		jdbc.update("""
				DELETE FROM shipment_risk_alert_deliveries
				WHERE transition_id IN (
					SELECT transition.id FROM shipment_risk_transitions transition
					JOIN shipments shipment ON shipment.id = transition.shipment_id
					WHERE shipment.shipment_reference LIKE ?
				)
				""", shipmentPattern);
		jdbc.update("DELETE FROM shipment_risk_transitions WHERE shipment_id IN (SELECT id FROM shipments WHERE shipment_reference LIKE ?)",
				shipmentPattern);
		jdbc.update("DELETE FROM shipments WHERE shipment_reference LIKE ?", shipmentPattern);
		jdbc.update("DELETE FROM users WHERE email LIKE 'sweep-%@example.test'");
	}
}
