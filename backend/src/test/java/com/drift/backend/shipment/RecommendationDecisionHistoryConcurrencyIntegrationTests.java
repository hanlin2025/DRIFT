package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
class RecommendationDecisionHistoryConcurrencyIntegrationTests {

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired RecommendationDecisionService decisions;
	@Autowired RecommendationDecisionRepository storedDecisions;
	@Autowired RecommendationDecisionHistoryRepository history;
	@Autowired PlatformTransactionManager transactions;
	@Autowired JdbcTemplate jdbc;

	@Test
	void concurrentDecisionsLeaveOneCurrentRowMatchingTheLastAcceptedHistoryId() throws Exception {
		Fixture fixture = seed();
		Shipment shipment = fixture.shipment();
		UserAccount first = fixture.first();
		UserAccount second = fixture.second();
		Instant tied = Instant.parse("2026-10-20T04:00:00Z");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> approve = pool.submit(() -> {
				start.await();
				decisions.approve(shipment, first, tied, ShipmentRecommendationImpact.noPredictedImpact());
				return null;
			});
			Future<?> dismiss = pool.submit(() -> {
				start.await();
				decisions.dismiss(shipment, second, tied, ShipmentRecommendationImpact.noPredictedImpact());
				return null;
			});
			start.countDown();
			approve.get(30, TimeUnit.SECONDS);
			dismiss.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			entityManager.clear();
			List<RecommendationDecisionHistory> rows = history.findForShipmentNewestFirst(shipment.getId());
			assertThat(rows).hasSize(2);
			assertThat(rows).allSatisfy(row -> assertThat(row.getDecidedAt()).isEqualTo(tied));
			assertThat(rows.get(0).getId()).isGreaterThan(rows.get(1).getId());
			RecommendationDecision current = storedDecisions.findById(shipment.getId()).orElseThrow();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_decisions WHERE shipment_id = ?",
					Integer.class, shipment.getId())).isEqualTo(1);
			assertThat(current.getOutcome()).isEqualTo(rows.get(0).getOutcome());
			assertThat(current.getDecidedBy().getId()).isEqualTo(rows.get(0).getDecidedBy().getId());
			assertThat(current.getDecidedAt()).isEqualTo(rows.get(0).getDecidedAt());
			assertThat(current.getRecommendationWording()).isEqualTo(rows.get(0).getRecommendationWording());
		});
	}

	private Fixture seed() {
		return new TransactionTemplate(transactions).execute(status -> {
			Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			AccountRole role = entityManager
					.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
					.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
			UserAccount actor = new UserAccount("Concurrent Actor", "race-" + UUID.randomUUID() + "@example.com",
					"hash", role, company);
			UserAccount other = new UserAccount("Concurrent Other", "race-" + UUID.randomUUID() + "@example.com",
					"hash", role, company);
			entityManager.persist(actor);
			entityManager.persist(other);
			Shipment shipment = new Shipment(company, actor, "RACE-" + UUID.randomUUID(), "Jakarta", "Rotterdam",
					"Singapore", "Mother", OffsetDateTime.parse("2026-10-15T08:00:00Z"), "Feeder",
					OffsetDateTime.parse("2026-10-17T08:00:00Z"));
			entityManager.persist(shipment);
			entityManager.flush();
			return new Fixture(shipment, actor, other);
		});
	}

	private record Fixture(Shipment shipment, UserAccount first, UserAccount second) {
	}
}
