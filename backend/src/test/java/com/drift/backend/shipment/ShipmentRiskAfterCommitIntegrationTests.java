package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import com.drift.backend.shipment.csvimport.ShipmentImportRow;

@SpringBootTest
@ActiveProfiles("test")
class ShipmentRiskAfterCommitIntegrationTests {
	@Autowired TransactionTemplate transactions;
	@Autowired CompanyRepository companies;
	@Autowired UserAccountRepository users;
	@Autowired AccountRoleRepository roles;
	@Autowired ShipmentService shipments;
	@Autowired ShipmentRepository shipmentRepository;
	@Autowired ShipmentRiskAlertActivationService activation;
	@Autowired ShipmentRiskTransitionRepository transitions;
	@Autowired ShipmentRiskAlertDeliveryRepository deliveries;
	@Autowired ShipmentRiskEvaluator evaluator;

	private AuthenticatedUser principal;

	@BeforeEach
	void activateAndCreateEligibleUser() {
		transactions.executeWithoutResult(status -> {
			Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			AccountRole role = roles.findByCode(Role.LOGISTICS_MANAGER).orElseThrow();
			UserAccount user = new UserAccount("After commit", "after-" + UUID.randomUUID() + "@example.test", "hash", role, company);
			user.chooseRiskNotifications(true, RiskNotificationLevel.LOW);
			users.save(user);
			principal = new AuthenticatedUser(user.getId(), user.getEmail(), Role.LOGISTICS_MANAGER, java.time.Instant.now());
		});
		activation.activate();
	}

	@Test
	void committedCreationCreatesOneInitialTransitionAndRecipientDelivery() {
		String reference = "CDG198-AFTER-" + UUID.randomUUID();
		ShipmentResponse response = shipments.create(principal, request(reference));
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(response.id())).hasSize(1);
		ShipmentRiskTransition transition = transitions.findByShipmentIdOrderBySequenceAsc(response.id()).getFirst();
		assertThat(transition.getTransitionType()).isEqualTo(ShipmentRiskTransitionType.INITIAL);
		assertThat(deliveries.findByTransitionIdOrderByIdAsc(transition.getId()))
				.extracting(delivery -> delivery.getUser().getId())
				.contains(principal.id());
		assertThat(evaluator.evaluate(response.id())).isEqualTo(ShipmentRiskEvaluationOutcome.NO_TRANSITION);
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(response.id())).hasSize(1);
	}

	@Test
	void rolledBackCreationDoesNotPublishAnAfterCommitEvaluation() {
		String reference = "CDG198-ROLLBACK-" + UUID.randomUUID();
		int transitionCountBefore = transitions.findAll().size();
		transactions.executeWithoutResult(status -> {
			shipments.create(principal, request(reference));
			status.setRollbackOnly();
		});
		assertThat(shipmentRepository.findAll().stream().map(Shipment::getShipmentReference)).doesNotContain(reference);
		assertThat(transitions.findAll()).hasSize(transitionCountBefore);
	}

	@Test
	void committedScheduleUpdateEvaluatesTheNewScheduleAfterCommit() {
		ShipmentResponse created = shipments.create(principal, request("CDG198-SCHEDULE-" + UUID.randomUUID()));
		OffsetDateTime arrival = created.plannedMotherArrivalAt().plusHours(1);
		shipments.update(principal, created.id(), new UpdateShipmentRequest(created.shipmentReference(), created.origin(),
				created.destination(), created.transshipmentPort(), created.motherVessel(), arrival, created.feederVessel(),
				arrival.plusHours(11), null, null, null, null, null, null, created.version()));

		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(created.id()))
				.extracting(ShipmentRiskTransition::getTransitionType)
				.containsExactly(ShipmentRiskTransitionType.INITIAL, ShipmentRiskTransitionType.RISE);
	}

	@Test
	void committedCsvImportCreatesAnInitialTransitionAfterCommit() {
		String reference = "CDG198-IMPORT-" + UUID.randomUUID();
		OffsetDateTime arrival = OffsetDateTime.now().plusDays(2);
		ShipmentImportRow row = new ShipmentImportRow(1, reference, "A", "B", "C", "Mother", arrival,
				"Feeder", arrival.plusHours(25), null);
		transactions.executeWithoutResult(status -> {
			UserAccount creator = users.findById(principal.id()).orElseThrow();
			shipments.createImported(creator.getCompany(), creator, row, null);
		});
		Shipment shipment = shipmentRepository.findAll().stream()
				.filter(candidate -> reference.equals(candidate.getShipmentReference())).findFirst().orElseThrow();
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(shipment.getId()))
				.extracting(ShipmentRiskTransition::getTransitionType)
				.containsExactly(ShipmentRiskTransitionType.INITIAL);
	}

	@Test
	void rolledBackCsvImportDoesNotCreateShipmentOrTransition() {
		String reference = "CDG198-IMPORT-ROLLBACK-" + UUID.randomUUID();
		int transitionCountBefore = transitions.findAll().size();
		OffsetDateTime arrival = OffsetDateTime.now().plusDays(2);
		transactions.executeWithoutResult(status -> {
			UserAccount creator = users.findById(principal.id()).orElseThrow();
			shipments.createImported(creator.getCompany(), creator, new ShipmentImportRow(1, reference, "A", "B", "C",
					"Mother", arrival, "Feeder", arrival.plusHours(25), null), null);
			status.setRollbackOnly();
		});
		assertThat(shipmentRepository.findAll().stream().map(Shipment::getShipmentReference)).doesNotContain(reference);
		assertThat(transitions.findAll()).hasSize(transitionCountBefore);
	}

	@Test
	void metadataUpdateAndArchiveDoNotCreateAdditionalRiskTransitions() {
		ShipmentResponse created = shipments.create(principal, request("CDG198-METADATA-" + UUID.randomUUID()));
		ShipmentResponse metadataUpdate = shipments.update(principal, created.id(), new UpdateShipmentRequest(
				created.shipmentReference(), "Updated origin", created.destination(), created.transshipmentPort(),
				created.motherVessel(), created.plannedMotherArrivalAt(), created.feederVessel(),
				created.plannedFeederDepartureAt(), null, null, null, null, null, null, created.version()));
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(created.id())).hasSize(1);

		shipments.archive(principal, metadataUpdate.id());
		assertThat(shipmentRepository.findById(created.id()).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.ARCHIVED);
		assertThat(transitions.findByShipmentIdOrderBySequenceAsc(created.id())).hasSize(1);
	}

	private static CreateShipmentRequest request(String reference) {
		OffsetDateTime arrival = OffsetDateTime.now().plusDays(2);
		return new CreateShipmentRequest(reference, "A", "B", "C", "Mother", arrival, "Feeder", arrival.plusHours(25), null, null, null, null, null, null);
	}
}
