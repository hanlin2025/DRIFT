package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.shipment.ShipmentRecommendedResponse.Code;
import com.drift.backend.shipment.exception.RecommendationDecisionRejectedException;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecommendationDecisionServiceIntegrationTests {

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired RecommendationDecisionService decisions;
	@Autowired RecommendationDecisionRepository storedDecisions;
	@Autowired JdbcTemplate jdbc;

	private Shipment shipment;
	private UserAccount actor;
	private UserAccount laterActor;
	private Instant decidedAt;

	@BeforeEach
	void setUp() {
		Company company = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
		actor = new UserAccount("Decision Actor", "record-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		laterActor = new UserAccount("Later Actor", "record-" + UUID.randomUUID() + "@example.com", "hash", role, company);
		entityManager.persist(actor);
		entityManager.persist(laterActor);
		shipment = new Shipment(company, actor, "REC-" + UUID.randomUUID(), "Jakarta", "Rotterdam", "Singapore",
				"Mother", OffsetDateTime.parse("2026-10-15T08:00:00Z"), "Feeder",
				OffsetDateTime.parse("2026-10-17T08:00:00Z"));
		entityManager.persist(shipment);
		entityManager.flush();
		decidedAt = Instant.parse("2026-10-20T01:00:00Z");
	}

	@Test
	void currentReadStoresNothingWhenTheImpactHasNotBeenReturned() {
		Shipment before = snapshot();

		assertThatThrownBy(() -> decisions.approve(shipment, actor, decidedAt))
				.isInstanceOf(RecommendationDecisionRejectedException.class)
				.hasMessage(RecommendationDecisionRejectedException.UNAVAILABLE);
		assertThatThrownBy(() -> decisions.modify(shipment, actor, decidedAt, Code.MONITOR_CONSTRAINTS))
				.isInstanceOf(RecommendationDecisionRejectedException.class)
				.hasMessage(RecommendationDecisionRejectedException.UNAVAILABLE);
		assertThatThrownBy(() -> decisions.dismiss(shipment, actor, decidedAt))
				.isInstanceOf(RecommendationDecisionRejectedException.class)
				.hasMessage(RecommendationDecisionRejectedException.UNAVAILABLE);

		assertNothingStored(before);
	}

	@Test
	void approvalStoresTheRecommendationItWasMadeAgainst() {
		Shipment before = snapshot();

		RecommendationDecision decision = decisions.approve(shipment, actor, decidedAt,
				ShipmentRecommendationImpact.noPredictedImpact());
		entityManager.clear();

		RecommendationDecision stored = storedDecisions.findById(shipment.getId()).orElseThrow();
		assertThat(stored.getShipmentId()).isEqualTo(decision.getShipmentId());
		assertThat(stored.getOutcome()).isEqualTo(RecommendationDecisionOutcome.APPROVED);
		assertThat(stored.getRecommendationName()).isEqualTo(Code.NO_ACTION.label());
		assertThat(stored.getRecommendationWording()).isEqualTo(Code.NO_ACTION.wording());
		assertThat(stored.getRecommendationWording()).doesNotContain("planned connection window");
		assertThat(stored.getSelectedResponseName()).isNull();
		assertThat(stored.getSelectedResponseWording()).isNull();
		assertThat(stored.getDecidedBy().getId()).isEqualTo(actor.getId());
		assertThat(stored.getDecidedAt()).isEqualTo(decidedAt);
		assertShipmentUnchanged(before);
		assertNoRiskOrMailRows();
	}

	@Test
	void modificationStoresAnotherResponseAndDismissalSetsTheRecommendationAside() {
		Shipment before = snapshot();
		decisions.modify(shipment, actor, decidedAt, Code.MONITOR_CONSTRAINTS,
				ShipmentRecommendationImpact.noPredictedImpact());
		entityManager.clear();

		RecommendationDecision modified = storedDecisions.findById(shipment.getId()).orElseThrow();
		assertThat(modified.getOutcome()).isEqualTo(RecommendationDecisionOutcome.MODIFIED);
		assertThat(modified.getRecommendationName()).isEqualTo("No action");
		assertThat(modified.getRecommendationWording()).isEqualTo(Code.NO_ACTION.wording());
		assertThat(modified.getSelectedResponseName()).isEqualTo("Monitor");
		assertThat(modified.getSelectedResponseWording()).isEqualTo(Code.MONITOR_CONSTRAINTS.wording());

		Instant dismissedAt = decidedAt.plusSeconds(60);
		decisions.dismiss(shipment, laterActor, dismissedAt, ShipmentRecommendationImpact.noPredictedImpact());
		entityManager.clear();

		RecommendationDecision dismissed = storedDecisions.findById(shipment.getId()).orElseThrow();
		assertThat(dismissed.getOutcome()).isEqualTo(RecommendationDecisionOutcome.DISMISSED);
		assertThat(dismissed.getRecommendationName()).isEqualTo("No action");
		assertThat(dismissed.getSelectedResponseName()).isNull();
		assertThat(dismissed.getSelectedResponseWording()).isNull();
		assertThat(dismissed.getDecidedBy().getId()).isEqualTo(laterActor.getId());
		assertThat(dismissed.getDecidedAt()).isEqualTo(dismissedAt);
		assertThat(decisionCount()).isEqualTo(1);
		assertShipmentUnchanged(before);
		assertNoRiskOrMailRows();
	}

	@Test
	void aLaterDecisionReplacesTheCurrentRowWithTheRecommendationAtThatTime() {
		decisions.approve(shipment, actor, decidedAt, ShipmentRecommendationImpact.noPredictedImpact());
		Instant later = decidedAt.plusSeconds(120);
		decisions.approve(shipment, laterActor, later, ShipmentRecommendationImpact.affectsConstraints());
		entityManager.clear();

		RecommendationDecision stored = storedDecisions.findById(shipment.getId()).orElseThrow();
		assertThat(stored.getOutcome()).isEqualTo(RecommendationDecisionOutcome.APPROVED);
		assertThat(stored.getRecommendationName()).isEqualTo("Monitor");
		assertThat(stored.getRecommendationWording()).isEqualTo(Code.MONITOR_CONSTRAINTS.wording());
		assertThat(stored.getDecidedBy().getId()).isEqualTo(laterActor.getId());
		assertThat(stored.getDecidedAt()).isEqualTo(later);
		assertThat(decisionCount()).isEqualTo(1);
	}

	@Test
	void anUnavailableRecommendationDoesNotReplaceTheStoredDecision() {
		decisions.approve(shipment, actor, decidedAt, ShipmentRecommendationImpact.noPredictedImpact());

		assertThatThrownBy(() -> decisions.dismiss(shipment, laterActor, decidedAt.plusSeconds(30),
				ShipmentRecommendationImpact.unavailable("The berth is closed.")))
				.isInstanceOf(RecommendationDecisionRejectedException.class)
				.hasMessage(RecommendationDecisionRejectedException.UNAVAILABLE);

		entityManager.clear();
		RecommendationDecision stored = storedDecisions.findById(shipment.getId()).orElseThrow();
		assertThat(stored.getOutcome()).isEqualTo(RecommendationDecisionOutcome.APPROVED);
		assertThat(stored.getRecommendationName()).isEqualTo("No action");
		assertThat(stored.getDecidedBy().getId()).isEqualTo(actor.getId());
		assertThat(decisionCount()).isEqualTo(1);
	}

	@Test
	void anArchivedShipmentRejectsANewDecisionAndKeepsTheStoredOne() {
		decisions.approve(shipment, actor, decidedAt, ShipmentRecommendationImpact.noPredictedImpact());
		entityManager.clear();
		Shipment current = entityManager.find(Shipment.class, shipment.getId());
		current.archive();
		entityManager.flush();
		Shipment before = snapshot();

		assertThatThrownBy(() -> decisions.approve(current, laterActor, decidedAt.plusSeconds(30),
				ShipmentRecommendationImpact.noPredictedImpact()))
				.isInstanceOf(RecommendationDecisionRejectedException.class)
				.hasMessage(RecommendationDecisionRejectedException.ARCHIVED);

		entityManager.clear();
		RecommendationDecision stored = storedDecisions.findById(shipment.getId()).orElseThrow();
		assertThat(stored.getOutcome()).isEqualTo(RecommendationDecisionOutcome.APPROVED);
		assertThat(stored.getDecidedBy().getId()).isEqualTo(actor.getId());
		assertThat(decisionCount()).isEqualTo(1);
		assertShipmentUnchanged(before);
	}

	@Test
	void aHighRiskApprovalStoresThatResponseRatherThanTheRiskExplanation() {
		Shipment urgent = new Shipment(shipment.getCompany(), actor, "URG-" + UUID.randomUUID(), "Jakarta", "Rotterdam",
				"Singapore", "Mother", OffsetDateTime.parse("2026-10-15T08:00:00Z"), "Feeder",
				OffsetDateTime.parse("2026-10-15T18:00:00Z"));
		entityManager.persist(urgent);
		entityManager.flush();
		shipment = urgent;

		RecommendationDecision watch = decisions.approve(urgent, actor, decidedAt,
				ShipmentRecommendationImpact.noPredictedImpact());
		assertThat(watch.getRecommendationName()).isEqualTo("Watch the connection");
		assertThat(watch.getRecommendationWording()).isEqualTo(Code.WATCH_CONNECTION.wording());
		assertThat(watch.getRecommendationWording()).doesNotContain("planned connection window");

		decisions.approve(urgent, laterActor, decidedAt.plusSeconds(30), ShipmentRecommendationImpact.affectsConstraints());
		entityManager.clear();
		RecommendationDecision prepare = storedDecisions.findById(urgent.getId()).orElseThrow();
		assertThat(prepare.getRecommendationName()).isEqualTo("Prepare an intervention");
		assertThat(prepare.getRecommendationWording()).isEqualTo(Code.PREPARE_INTERVENTION.wording());
		assertThat(prepare.getOutcome()).isEqualTo(RecommendationDecisionOutcome.APPROVED);
		assertThat(decisionCount()).isEqualTo(1);
		assertNoRiskOrMailRows();
	}

	@Test
	void aMissingSelectedResponseAndAnArchivedShipmentStoreNothing() {
		assertThatThrownBy(() -> decisions.modify(shipment, actor, decidedAt, null,
				ShipmentRecommendationImpact.noPredictedImpact()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A modification needs the selected response");
		assertThat(decisionCount()).isZero();

		entityManager.find(Shipment.class, shipment.getId()).archive();
		entityManager.flush();
		assertThatThrownBy(() -> decisions.approve(shipment, actor, decidedAt,
				ShipmentRecommendationImpact.noPredictedImpact()))
				.isInstanceOf(RecommendationDecisionRejectedException.class)
				.hasMessage(RecommendationDecisionRejectedException.ARCHIVED);
		assertThat(decisionCount()).isZero();
		assertThat(entityManager.find(Shipment.class, shipment.getId()).getStatus()).isEqualTo(ShipmentStatus.ARCHIVED);
	}

	@Test
	void anImporterLinkStaysInPlace() {
		Company importer = companies.findByCode("STRAITS_FRESH_DEMO").orElseThrow();
		shipment.linkImporter(importer);
		entityManager.flush();

		decisions.approve(shipment, actor, decidedAt, ShipmentRecommendationImpact.noPredictedImpact());
		entityManager.clear();

		assertThat(entityManager.find(Shipment.class, shipment.getId()).getImporterCompany().getId())
				.isEqualTo(importer.getId());
		assertThat(decisionCount()).isEqualTo(1);
	}

	@Test
	void aModificationCannotRepeatTheRecommendedResponse() {
		assertThatThrownBy(() -> decisions.modify(shipment, actor, decidedAt, Code.NO_ACTION,
				ShipmentRecommendationImpact.noPredictedImpact()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A modification selects a different response");
		assertThat(decisionCount()).isZero();
	}

	private Shipment snapshot() {
		entityManager.flush();
		entityManager.clear();
		return entityManager.find(Shipment.class, shipment.getId());
	}

	private void assertNothingStored(Shipment before) {
		entityManager.clear();
		assertThat(storedDecisions.findById(shipment.getId())).isEmpty();
		assertThat(decisionCount()).isZero();
		assertShipmentUnchanged(before);
		assertNoRiskOrMailRows();
	}

	private void assertShipmentUnchanged(Shipment before) {
		Shipment current = entityManager.find(Shipment.class, shipment.getId());
		assertThat(current.getStatus()).isEqualTo(before.getStatus());
		assertThat(current.getShipmentReference()).isEqualTo(before.getShipmentReference());
		assertThat(current.getPlannedMotherArrivalAt()).isEqualTo(before.getPlannedMotherArrivalAt());
		assertThat(current.getPlannedFeederDepartureAt()).isEqualTo(before.getPlannedFeederDepartureAt());
		assertThat(current.getImporterCompany()).isNull();
		assertThat(current.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
		assertThat(current.getUpdatedBy().getId()).isEqualTo(before.getUpdatedBy().getId());
		assertThat(current.getVersion()).isEqualTo(before.getVersion());
	}

	private void assertNoRiskOrMailRows() {
		assertThat(count("shipment_risk_states")).isZero();
		assertThat(count("shipment_risk_transitions")).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM shipment_risk_alert_deliveries delivery
				JOIN shipment_risk_transitions transition ON transition.id = delivery.transition_id
				WHERE transition.shipment_id = ?
				""", Integer.class, shipment.getId())).isZero();
	}

	private int decisionCount() {
		return count("recommendation_decisions");
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE shipment_id = ?", Integer.class,
				shipment.getId());
	}
}
