package com.drift.backend.maritimereassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.company.TestAuthorisations;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.shipment.CreateShipmentRequest;
import com.drift.backend.shipment.ShipmentLinkService;
import com.drift.backend.shipment.ShipmentService;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaritimeReassessmentIntegrationTests {

	private static final Instant CUTOFF = Instant.parse("2026-10-28T00:00:00Z");

	@Autowired MaritimeReassessmentService reassessments;
	@Autowired MaritimeReassessmentStore store;
	@Autowired ShipmentService shipments;
	@Autowired ShipmentLinkService links;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private Long ownerId;
	private Long importerId;
	private Long outsiderId;
	private AuthenticatedUser forwarder;
	private AuthenticatedUser importer;
	private AuthenticatedUser outsider;

	@BeforeEach
	void setUp() {
		ownerId = company("HARBOURLINE_DEMO");
		importerId = company("STRAITS_FRESH_DEMO");
		outsiderId = otherCompany();
		forwarder = account("forwarder", Role.FREIGHT_FORWARDER, ownerId);
		importer = account("importer", Role.IMPORTER, importerId);
		outsider = account("outsider", Role.FREIGHT_FORWARDER, outsiderId);
	}

	@Test
	void aClosedReviewDoesNotChangeTheShipmentAndARepeatStaysOneRow() {
		Long shipmentId = create("CDG266-UNCHANGED");
		Row before = row(shipmentId);
		int decisions = count("recommendation_decisions", shipmentId);
		int transitions = count("shipment_risk_transitions", shipmentId);
		int deliveries = deliveries(shipmentId);

		MaritimeReassessmentResult first = reassessments.review(forwarder, shipmentId, bundle());
		MaritimeReassessmentResult second = reassessments.review(forwarder, shipmentId, bundle());

		assertThat(first.alreadyRecorded()).isFalse();
		assertThat(second.alreadyRecorded()).isTrue();
		assertThat(first.version().validationStatus()).isEqualTo("PENDING_REVIEW");
		assertThat(first.version().provider()).isEqualTo("NONE");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE shipment_id = ?", Integer.class, shipmentId))
				.isEqualTo(1);
		assertThat(row(shipmentId)).isEqualTo(before);
		assertThat(count("recommendation_decisions", shipmentId)).isEqualTo(decisions);
		assertThat(count("shipment_risk_transitions", shipmentId)).isEqualTo(transitions);
		assertThat(deliveries(shipmentId)).isEqualTo(deliveries);
		assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
				.endsWith("31", "32", "33", "34", "35", "36", "37");
	}

	@Test
	void currentEvidenceIsRecordedOnceWhileTheContextStaysClosed() {
		Long shipmentId = create("CDG266-CONTEXT");

		MaritimeReassessmentResult first = reassessments.reviewCurrentEvidence(forwarder, shipmentId, "recorded-mpa");
		MaritimeReassessmentResult second = reassessments.reviewCurrentEvidence(forwarder, shipmentId, "recorded-mpa");

		assertThat(first.version().validationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
		assertThat(second.alreadyRecorded()).isTrue();
		assertThat(first.version().interpretation()).isNull();
	}

	@Test
	void historyKeepsTheValidatedAssessmentAfterALaterFailureAndHidesItFromAnotherCompany() {
		Long shipmentId = create("CDG267-HISTORY");
		MutableProvider provider = new MutableProvider();
		MaritimeReassessmentOrchestrator orchestrator = new MaritimeReassessmentOrchestrator(store, provider, approved(),
				new MoveableClock());
		orchestrator.review(shipmentId, ownerId, bundle("eta-1", "before"));
		provider.fail = true;
		orchestrator.review(shipmentId, ownerId, bundle("eta-2", "after"));

		MaritimeReassessmentHistory history = reassessments.history(forwarder, shipmentId);
		assertThat(history.versions()).hasSize(2);
		assertThat(history.latestAttempt().validationStatus()).isEqualTo("PROVIDER_FAILED");
		assertThat(history.latestValidated().snapshotIds()).containsExactly("eta-1");
		assertThat(history.latestValidated().outputJson()).contains("cdg267.reassessment-linkage.v1");
		assertThat(history.versions()).extracting(MaritimeReassessmentVersion::assessmentCutoffUtc)
				.containsOnly(CUTOFF);

		TestAuthorisations.allowShipment(jdbc, shipmentId, importerId);
		links.link(forwarder, shipmentId, importerId);
		assertThat(reassessments.history(importer, shipmentId).latestValidated().assessmentId())
				.isEqualTo(history.latestValidated().assessmentId());
		assertThatThrownBy(() -> reassessments.history(outsider, shipmentId))
				.isInstanceOf(ShipmentDetailForbiddenException.class);
		assertThatThrownBy(() -> reassessments.history(forwarder, 999_999_999L))
				.isInstanceOf(ShipmentNotFoundException.class);

		shipments.archive(account("manager", Role.LOGISTICS_MANAGER, ownerId), shipmentId);
		assertThat(reassessments.history(forwarder, shipmentId).versions()).hasSize(2);
	}

	private MaritimeEvidenceBundle bundle() {
		return bundle("eta-1", "before");
	}

	private MaritimeEvidenceBundle bundle(String evidenceId, String current) {
		return new MaritimeEvidenceBundle("recorded-mpa", CUTOFF, List.of(new MaritimeEvidence(
				"MPA_ETA_REVISION", "RECORDED_REPLAY", evidenceId, "title", "source", "", "citation",
				"2026-10-29 02:00:00", "2026-10-26T15:46:03Z", "2026-10-26T15:46:03Z", "HISTORICAL",
				"before", current, "+1", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", true, true,
				"UNVERIFIED_IMO_ONLY", "Recorded limitation.")), List.of(evidenceId));
	}

	private static MaritimeReassessmentLimits approved() {
		return new MaritimeReassessmentLimits(true, Set.of("recorded-mpa"), Set.of("MPA_ETA_REVISION"), 1, 4, 2,
				Duration.ofSeconds(2), 1);
	}

	private Long create(String reference) {
		return shipments.create(forwarder, new CreateShipmentRequest(reference, "Shanghai, CN", "Jakarta, ID", "Singapore",
				"MV Pacific Horizon", OffsetDateTime.parse("2026-10-15T08:00:00Z"), "MV Strait Runner",
				OffsetDateTime.parse("2026-10-16T12:00:00Z"), null, null, null, null, null, null)).id();
	}

	private Row row(Long shipmentId) {
		return jdbc.queryForObject("""
				SELECT updated_at, version, planned_mother_arrival_at, planned_feeder_departure_at, status
				FROM shipments WHERE id = ?
				""", (result, index) -> new Row(result.getTimestamp(1).toInstant(), result.getLong(2),
						result.getTimestamp(3).toInstant(), result.getTimestamp(4).toInstant(), result.getString(5)),
				shipmentId);
	}

	private int count(String table, Long shipmentId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE shipment_id = ?", Integer.class, shipmentId);
	}

	private int deliveries(Long shipmentId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM shipment_risk_alert_deliveries delivery
				JOIN shipment_risk_transitions transition ON transition.id = delivery.transition_id
				WHERE transition.shipment_id = ?
				""", Integer.class, shipmentId);
	}

	private AuthenticatedUser account(String prefix, Role role, Long companyId) {
		String email = "cdg266-" + prefix + "-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 266 User', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role.name());
		Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		return new AuthenticatedUser(id, email, role, Instant.now().plusSeconds(3600));
	}

	private Long company(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long otherCompany() {
		String code = ("CDG266" + UUID.randomUUID().toString().replace("-", "")).substring(0, 12);
		jdbc.update("INSERT INTO companies (code, name) VALUES (?, 'Other Co')", code);
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private record Row(Instant updatedAt, long version, Instant motherArrival, Instant feederDeparture, String status) {
	}

	private static final class MutableProvider implements MaritimeReassessmentProvider {
		private boolean fail;

		@Override
		public boolean opensNetwork() { return true; }

		@Override
		public MaritimeProviderReply assess(MaritimeProviderRequest request) {
			if (fail) {
				throw new IllegalStateException("down");
			}
			return new MaritimeProviderReply("test-provider", "test-model", "prompt-1", "Unverified recorded revision.",
					"UNVERIFIED_IMO_ONLY", false, false);
		}
	}

	private static final class MoveableClock extends java.time.Clock {
		private Instant now = CUTOFF;

		@Override
		public java.time.ZoneId getZone() { return java.time.ZoneId.of("UTC"); }

		@Override
		public java.time.Clock withZone(java.time.ZoneId zone) { return this; }

		@Override
		public Instant instant() {
			now = now.plusSeconds(1);
			return now;
		}
	}
}
