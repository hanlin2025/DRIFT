package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
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
class RecommendationDecisionHistorySchemaIntegrationTests {

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired RecommendationDecisionRepository decisions;
	@Autowired RecommendationDecisionHistoryRepository history;
	@Autowired JdbcTemplate jdbc;

	private Shipment shipment;
	private UserAccount actor;
	private UserAccount laterActor;

	@BeforeEach
	void setUp() {
		Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
		actor = new UserAccount("Decision Actor", "history-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		laterActor = new UserAccount("Later Actor", "history-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		entityManager.persist(actor);
		entityManager.persist(laterActor);
		shipment = new Shipment(company, actor, "HIST-" + UUID.randomUUID(), "Jakarta", "Rotterdam", "Singapore",
				"Mother", OffsetDateTime.parse("2026-10-15T08:00:00Z"), "Feeder",
				OffsetDateTime.parse("2026-10-16T08:00:00Z"));
		entityManager.persist(shipment);
		entityManager.flush();
	}

	@Test
	void repositoryExposesInsertAndReadOnly() {
		assertThat(Arrays.stream(RecommendationDecisionHistoryRepository.class.getMethods()).map(Method::getName))
				.containsExactlyInAnyOrder("insert", "findForShipmentNewestFirst");
	}

	@Test
	void currentDecisionRemainsOneRowWhileHistoryCanHoldMany() {
		Instant first = Instant.parse("2026-10-20T01:00:00Z");
		decisions.saveAndFlush(RecommendationDecision.approve(
				shipment, "Hold connection", "Keep the planned connection.", actor, first));
		history.insert(historyRow(RecommendationDecisionOutcome.APPROVED, "Hold connection", "Keep the planned connection.",
				null, null, actor, first));
		history.insert(historyRow(RecommendationDecisionOutcome.MODIFIED, "Hold connection", "Keep the planned connection.",
				"Advance feeder", "Move the feeder departure earlier.", laterActor, first.plusSeconds(60)));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_decisions WHERE shipment_id = ?",
				Integer.class, shipment.getId())).isEqualTo(1);
		assertThat(history.findForShipmentNewestFirst(shipment.getId())).hasSize(2);
		assertThat(jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
				.endsWith("29", "30", "31", "32", "33", "34", "35", "36", "37");
	}

	@Test
	void ordersEqualTimestampsByIdDescendingAndKeepsTheDecisionTimeActorName() {
		Instant tied = Instant.parse("2026-10-20T04:00:00Z");
		RecommendationDecisionHistory earlierId = history.insert(historyRow(RecommendationDecisionOutcome.APPROVED,
				"Hold connection", "Keep the planned connection.", null, null, actor, tied));
		RecommendationDecisionHistory laterId = history.insert(historyRow(RecommendationDecisionOutcome.DISMISSED,
				"Hold connection", "Keep the planned connection.", null, null, laterActor, tied));
		RecommendationDecisionHistory older = history.insert(historyRow(RecommendationDecisionOutcome.MODIFIED,
				"Hold connection", "Keep the planned connection.", "Advance feeder", "Move the feeder.",
				actor, tied.minusSeconds(30)));
		entityManager.clear();

		assertThat(history.findForShipmentNewestFirst(shipment.getId()))
				.extracting(RecommendationDecisionHistory::getId)
				.containsExactly(laterId.getId(), earlierId.getId(), older.getId());

		entityManager.clear();
		jdbc.update("UPDATE users SET full_name = ? WHERE id = ?", "Changed Name", actor.getId());
		entityManager.clear();

		RecommendationDecisionHistory stored = history.findForShipmentNewestFirst(shipment.getId()).get(1);
		assertThat(stored.getDecidedBy().getId()).isEqualTo(actor.getId());
		assertThat(stored.getDecidedByName()).isEqualTo("Decision Actor");
		assertThat(stored.getDecidedAt()).isEqualTo(tied);
		assertThat(entityManager.find(UserAccount.class, actor.getId()).getFullName()).isEqualTo("Changed Name");
	}

	@Test
	void databaseRejectsAnUnknownHistoryOutcome() {
		assertThatThrownBy(() -> insertHistory("IGNORED", "Hold connection", "Keep it.", null, null, "Decision Actor"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsBlankHistoryRecommendationText() {
		assertThatThrownBy(() -> insertHistory("APPROVED", "   ", "Keep it.", null, null, "Decision Actor"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsASelectedResponseOnHistoryApproval() {
		assertThatThrownBy(() -> insertHistory("APPROVED", "Hold connection", "Keep it.", "Advance feeder", "Move it.",
				"Decision Actor"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsAHistoryModificationThatRepeatsTheRecommendationName() {
		assertThatThrownBy(() -> insertHistory("MODIFIED", "Hold connection", "Keep it.", "Hold connection", "Move it.",
				"Decision Actor"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsAHistoryModificationWhoseNameDiffersOnlyByWhitespace() {
		assertThatThrownBy(() -> insertHistory("MODIFIED", "Hold connection", "Keep it.", " Hold connection ", "Move it.",
				"Decision Actor"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsABlankHistoryActorName() {
		assertThatThrownBy(() -> insertHistory("DISMISSED", "Hold connection", "Keep it.", null, null, "   "))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private RecommendationDecisionHistory historyRow(RecommendationDecisionOutcome outcome, String name, String wording,
			String selectedName, String selectedWording, UserAccount decidedBy, Instant decidedAt) {
		return RecommendationDecisionHistory.record(shipment, outcome, name, wording, selectedName, selectedWording,
				decidedBy, decidedBy.getFullName(), decidedAt);
	}

	private void insertHistory(String outcome, String name, String wording, String selectedName, String selectedWording,
			String actorName) {
		jdbc.update("""
				INSERT INTO recommendation_decision_history (
					shipment_id, outcome, recommendation_name, recommendation_wording,
					selected_response_name, selected_response_wording,
					decided_by_user_id, decided_by_name, decided_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				shipment.getId(), outcome, name, wording, selectedName, selectedWording, actor.getId(), actorName,
				Timestamp.from(Instant.parse("2026-10-20T01:00:00Z")));
	}
}
