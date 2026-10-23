package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

@SpringBootTest
@ActiveProfiles("test")
class ShipmentRiskAlertActivationShipmentCreationRaceIntegrationTests {
	@Autowired TransactionTemplate transactions;
	@Autowired CompanyRepository companies;
	@Autowired AccountRoleRepository roles;
	@Autowired UserAccountRepository users;
	@Autowired ShipmentService shipments;
	@Autowired ShipmentRiskAlertActivationService activation;
	@Autowired ShipmentRiskStateRepository states;
	@Autowired ShipmentRiskTransitionRepository transitions;

	private AuthenticatedUser principal;

	@BeforeEach
	void createUserBeforeTheRace() {
		transactions.executeWithoutResult(status -> {
			Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			AccountRole role = roles.findByCode(Role.LOGISTICS_MANAGER).orElseThrow();
			UserAccount user = users.save(new UserAccount("Race", "race-" + UUID.randomUUID() + "@example.test", "hash", role, company));
			principal = new AuthenticatedUser(user.getId(), user.getEmail(), Role.LOGISTICS_MANAGER, Instant.now());
		});
	}

	@Test
	void activationAndShipmentCreationCommitWithOneConsistentGeneration() throws Exception {
		String reference = "CDG198-RACE-" + UUID.randomUUID();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<ShipmentRiskAlertActivationResult> activated = executor.submit(() -> {
				ready.countDown(); start.await(10, TimeUnit.SECONDS); return activation.activate();
			});
			Future<ShipmentResponse> created = executor.submit(() -> {
				ready.countDown(); start.await(10, TimeUnit.SECONDS); return shipments.create(principal, request(reference));
			});
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(activated.get(30, TimeUnit.SECONDS)).isEqualTo(ShipmentRiskAlertActivationResult.ACTIVATED);
			ShipmentResponse shipment = created.get(30, TimeUnit.SECONDS);
			assertThat(states.findById(shipment.id()).orElseThrow().getActivationGeneration()).isEqualTo(1);
			assertThat(transitions.findByShipmentIdOrderBySequenceAsc(shipment.id()))
				.allSatisfy(transition -> assertThat(transition.getActivationGeneration()).isEqualTo(1));
		} finally {
			executor.shutdownNow();
		}
	}

	private static CreateShipmentRequest request(String reference) {
		OffsetDateTime arrival = OffsetDateTime.now().plusDays(2);
		return new CreateShipmentRequest(reference, "A", "B", "C", "Mother", arrival, "Feeder", arrival.plusHours(25), null, null, null, null, null, null);
	}
}
