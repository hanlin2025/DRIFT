package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
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
class RecommendationDecisionSchemaIntegrationTests {

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired RecommendationDecisionRepository decisions;
	@Autowired JdbcTemplate jdbc;

	private Shipment shipment;
	private UserAccount actor;
	private UserAccount laterActor;

	@BeforeEach
	void setUp() {
		Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
		actor = new UserAccount("Decision Actor", "decision-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		laterActor = new UserAccount("Later Actor", "decision-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		entityManager.persist(actor);
		entityManager.persist(laterActor);
		shipment = new Shipment(company, actor, "DEC-" + UUID.randomUUID(), "Jakarta", "Rotterdam", "Singapore",
				"Mother", OffsetDateTime.parse("2026-10-15T08:00:00Z"), "Feeder",
				OffsetDateTime.parse("2026-10-16T08:00:00Z"));
		entityManager.persist(shipment);
		entityManager.flush();
	}

	@Test
	void storesOneCurrentDecisionAndALaterDecisionReplacesIt() {
		Instant first = Instant.parse("2026-10-20T01:00:00Z");
		RecommendationDecision decision = decisions.saveAndFlush(RecommendationDecision.approve(
				shipment, " Hold connection ", " Keep the planned connection. ", actor, first));
		entityManager.clear();

		RecommendationDecision stored = decisions.findById(shipment.getId()).orElseThrow();
		assertThat(stored.getShipmentId()).isEqualTo(shipment.getId());
		assertThat(stored.getOutcome()).isEqualTo(RecommendationDecisionOutcome.APPROVED);
		assertThat(stored.getRecommendationName()).isEqualTo("Hold connection");
		assertThat(stored.getRecommendationWording()).isEqualTo("Keep the planned connection.");
		assertThat(stored.getSelectedResponseName()).isNull();
		assertThat(stored.getSelectedResponseWording()).isNull();
		assertThat(stored.getDecidedBy().getId()).isEqualTo(actor.getId());
		assertThat(stored.getDecidedAt()).isEqualTo(first);

		Instant second = Instant.parse("2026-10-20T02:00:00Z");
		stored.replace(RecommendationDecisionOutcome.MODIFIED, "Hold connection", "Keep the planned connection.",
				"Advance feeder", "Move the feeder departure earlier.", laterActor, second);
		decisions.saveAndFlush(stored);
		entityManager.clear();

		RecommendationDecision replaced = decisions.findById(shipment.getId()).orElseThrow();
		assertThat(replaced.getShipmentId()).isEqualTo(decision.getShipmentId());
		assertThat(replaced.getOutcome()).isEqualTo(RecommendationDecisionOutcome.MODIFIED);
		assertThat(replaced.getSelectedResponseName()).isEqualTo("Advance feeder");
		assertThat(replaced.getSelectedResponseWording()).isEqualTo("Move the feeder departure earlier.");
		assertThat(replaced.getDecidedBy().getId()).isEqualTo(laterActor.getId());
		assertThat(replaced.getDecidedAt()).isEqualTo(second);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_decisions WHERE shipment_id = ?",
				Integer.class, shipment.getId())).isEqualTo(1);
		assertThat(jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
				.endsWith("26", "27");
	}

	@Test
	void dismissalAndApprovalStoreNoSelectedResponse() {
		Instant decidedAt = Instant.parse("2026-10-20T01:00:00Z");
		decisions.saveAndFlush(RecommendationDecision.dismiss(
				shipment, " Hold connection ", " Keep the planned connection. ", actor, decidedAt));
		entityManager.clear();

		RecommendationDecision dismissed = decisions.findById(shipment.getId()).orElseThrow();
		assertThat(dismissed.getOutcome()).isEqualTo(RecommendationDecisionOutcome.DISMISSED);
		assertThat(dismissed.getRecommendationName()).isEqualTo("Hold connection");
		assertThat(dismissed.getSelectedResponseName()).isNull();
		assertThat(dismissed.getSelectedResponseWording()).isNull();

		dismissed.replace(RecommendationDecisionOutcome.APPROVED, "Hold connection", "Keep the planned connection.",
				null, null, laterActor, decidedAt.plusSeconds(60));
		decisions.saveAndFlush(dismissed);
		entityManager.clear();

		RecommendationDecision approved = decisions.findById(shipment.getId()).orElseThrow();
		assertThat(approved.getOutcome()).isEqualTo(RecommendationDecisionOutcome.APPROVED);
		assertThat(approved.getSelectedResponseName()).isNull();
		assertThat(approved.getSelectedResponseWording()).isNull();
		assertThat(approved.getDecidedBy().getId()).isEqualTo(laterActor.getId());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_decisions WHERE shipment_id = ?",
				Integer.class, shipment.getId())).isEqualTo(1);
	}

	@Test
	void rejectsBlankOrOverlongRecommendationText() {
		Instant decidedAt = Instant.parse("2026-10-20T01:00:00Z");
		assertThatThrownBy(() -> RecommendationDecision.approve(shipment, "  ", "Keep it.", actor, decidedAt))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Recommendation name is required");
		assertThatThrownBy(() -> RecommendationDecision.approve(shipment, "Hold connection", "  ", actor, decidedAt))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Recommendation wording is required");
		assertThatThrownBy(() -> RecommendationDecision.approve(shipment, "H".repeat(101), "Keep it.", actor, decidedAt))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Recommendation name is too long");
		assertThat(RecommendationDecision.approve(shipment, "H".repeat(100), " Keep it. ", actor, decidedAt)
				.getRecommendationName()).hasSize(100);
	}

	@Test
	void rejectsASelectedResponseOnApprovalOrDismissal() {
		Instant decidedAt = Instant.parse("2026-10-20T01:00:00Z");
		assertThatThrownBy(() -> RecommendationDecision.approve(shipment, "Hold connection", "Keep it.", actor, decidedAt)
				.replace(RecommendationDecisionOutcome.APPROVED, "Hold connection", "Keep it.",
						"Advance feeder", "Move the feeder.", actor, decidedAt))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A selected response is stored only for a modification");
		assertThatThrownBy(() -> RecommendationDecision.dismiss(shipment, "Hold connection", "Keep it.", actor, decidedAt)
				.replace(RecommendationDecisionOutcome.DISMISSED, "Hold connection", "Keep it.",
						"Advance feeder", null, actor, decidedAt))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsAModificationThatDoesNotSelectAnotherResponse() {
		Instant decidedAt = Instant.parse("2026-10-20T01:00:00Z");
		assertThatThrownBy(() -> RecommendationDecision.modify(shipment, "Hold connection", "Keep it.",
				null, "Move the feeder.", actor, decidedAt))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A modification needs the selected response");
		assertThatThrownBy(() -> RecommendationDecision.modify(shipment, "Hold connection", "Keep it.",
				" Hold connection ", "Move the feeder.", actor, decidedAt))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A modification selects a different response");
	}

	@Test
	void databaseRejectsASecondDecisionForTheSameShipment() {
		seedDismissal();
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO recommendation_decisions (
					shipment_id, outcome, recommendation_name, recommendation_wording, decided_by_user_id, decided_at)
				VALUES (?, 'APPROVED', 'Hold connection', 'Keep the planned connection.', ?, ?)
				""", shipment.getId(), actor.getId(), Timestamp.from(Instant.parse("2026-10-20T03:00:00Z"))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsAnUnknownOutcome() {
		seedDismissal();
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE recommendation_decisions SET outcome = 'IGNORED' WHERE shipment_id = ?", shipment.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsASelectedResponseOnApproval() {
		seedDismissal();
		assertThatThrownBy(() -> jdbc.update("""
				UPDATE recommendation_decisions
				SET outcome = 'APPROVED', selected_response_name = 'Advance feeder', selected_response_wording = 'Move it.'
				WHERE shipment_id = ?
				""", shipment.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsBlankRecommendationText() {
		seedDismissal();
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE recommendation_decisions SET recommendation_name = '   ' WHERE shipment_id = ?", shipment.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsAModificationThatRepeatsTheRecommendationName() {
		seedDismissal();
		assertThatThrownBy(() -> jdbc.update("""
				UPDATE recommendation_decisions
				SET outcome = 'MODIFIED',
					selected_response_name = recommendation_name,
					selected_response_wording = 'Move it.'
				WHERE shipment_id = ?
				""", shipment.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsAModificationWhoseNameDiffersOnlyByWhitespace() {
		seedDismissal();
		assertThatThrownBy(() -> jdbc.update("""
				UPDATE recommendation_decisions
				SET outcome = 'MODIFIED',
					selected_response_name = ' ' || recommendation_name || ' ',
					selected_response_wording = 'Move it.'
				WHERE shipment_id = ?
				""", shipment.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void seedDismissal() {
		decisions.saveAndFlush(RecommendationDecision.dismiss(
				shipment, "Hold connection", "Keep the planned connection.", actor, Instant.parse("2026-10-20T01:00:00Z")));
		entityManager.clear();
	}
}
