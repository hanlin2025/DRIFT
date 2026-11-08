package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
class RecommendationDecisionHistoryRollbackIntegrationTests {

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired RecommendationDecisionService decisions;
	@Autowired PlatformTransactionManager transactions;
	@Autowired JdbcTemplate jdbc;
	@MockitoSpyBean RecommendationDecisionRepository storedDecisions;

	@Test
	void aFailedCurrentWriteRollsTheHistoryInsertBack() {
		Fixture fixture = seed();
		doThrow(new IllegalStateException("current write failed")).when(storedDecisions)
				.saveAndFlush(any(RecommendationDecision.class));

		assertThatThrownBy(() -> decisions.approve(fixture.shipment(), fixture.actor(),
				Instant.parse("2026-10-20T01:00:00Z"), ShipmentRecommendationImpact.noPredictedImpact()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("current write failed");

		assertThat(count("recommendation_decisions", fixture.shipment().getId())).isZero();
		assertThat(count("recommendation_decision_history", fixture.shipment().getId())).isZero();
	}

	private Fixture seed() {
		return new TransactionTemplate(transactions).execute(status -> {
			Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			AccountRole role = entityManager
					.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
					.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
			UserAccount actor = new UserAccount("Rollback Actor", "rollback-" + UUID.randomUUID() + "@example.com",
					"hash", role, company);
			entityManager.persist(actor);
			Shipment shipment = new Shipment(company, actor, "RBK-" + UUID.randomUUID(), "Jakarta", "Rotterdam",
					"Singapore", "Mother", OffsetDateTime.parse("2026-10-15T08:00:00Z"), "Feeder",
					OffsetDateTime.parse("2026-10-17T08:00:00Z"));
			entityManager.persist(shipment);
			entityManager.flush();
			return new Fixture(shipment, actor);
		});
	}

	private record Fixture(Shipment shipment, UserAccount actor) {
	}

	private int count(String table, long shipmentId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE shipment_id = ?", Integer.class,
				shipmentId);
	}
}
