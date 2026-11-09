package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
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
import com.drift.backend.shipment.Shipment;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaritimeSnapshotChangeIntegrationTests {

	private static final String MOTHER = "9351048";

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired JdbcTemplate jdbc;
	@Autowired MaritimeSnapshotChangeDetector detector;

	private Long shipmentId;
	private java.sql.Timestamp updatedAt;

	@BeforeEach
	void setUp() {
		Company owner = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		AccountRole role = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
		UserAccount actor = new UserAccount("Snapshot Actor", "snapshot-" + UUID.randomUUID() + "@example.com", "hash", role, owner);
		entityManager.persist(actor);
		Shipment shipment = new Shipment(owner, actor, "CDG264-" + UUID.randomUUID(), "Jakarta", "Rotterdam", "Singapore",
				"KOTA LARIS", OffsetDateTime.parse("2026-10-28T08:00:00Z"), "FEEDER",
				OffsetDateTime.parse("2026-10-28T20:00:00Z"));
		entityManager.persist(shipment);
		entityManager.flush();
		shipmentId = shipment.getId();
		jdbc.update("UPDATE shipments SET mother_vessel_imo = ? WHERE id = ?", MOTHER, shipmentId);
		updatedAt = jdbc.queryForObject("SELECT updated_at FROM shipments WHERE id = ?", java.sql.Timestamp.class, shipmentId);
		entityManager.clear();
	}

	@Test
	void storesEachFingerprintOnceAndDoesNotTouchTheShipment() {
		MaritimeSnapshotChangeBatch first = detector.accept(List.of(revision("2026-10-26T15:46:03Z", "2026-10-29 02:00:00")));
		MaritimeSnapshotChangeBatch repeat = detector.accept(List.of(revision("2026-10-27T15:46:03Z", "2026-10-29 02:00:00")));
		MaritimeSnapshotChangeBatch revised = detector.accept(List.of(revision("2026-10-27T16:46:03Z", "2026-10-29 05:00:00")));

		assertThat(first.changes()).hasSize(1);
		assertThat(first.changes().getFirst().forwardLooking()).isTrue();
		assertThat(repeat.changes()).isEmpty();
		assertThat(revised.changes()).singleElement().extracting(MaritimeSnapshotChange::kind).isEqualTo("REVISED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_snapshot_observations", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_snapshot_observations WHERE head", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT source_mode FROM maritime_snapshot_observations WHERE head", String.class)).isEqualTo("LIVE");
		assertThat(jdbc.queryForObject("SELECT freshness FROM maritime_snapshot_observations WHERE head", String.class)).isEqualTo("APPLICABLE");
		assertThat(jdbc.queryForObject("SELECT source_event_at FROM maritime_snapshot_observations WHERE head", String.class))
				.isEqualTo("2026-10-29 05:00:00");
		assertThat(jdbc.queryForObject("SELECT updated_at FROM shipments WHERE id = ?", java.sql.Timestamp.class, shipmentId)).isEqualTo(updatedAt);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE shipment_id = ?", Integer.class, shipmentId)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT pg_get_constraintdef(oid) FROM pg_constraint
				WHERE conname = 'maritime_snapshot_observations_source_type_check'
				""", String.class)).contains("MPA_ETA_REVISION", "MPA_OFFICIAL_NOTICE", "NEA_WEATHER");
		assertThat(jdbc.queryForObject("""
				SELECT indexdef FROM pg_indexes WHERE indexname = 'maritime_snapshot_observations_one_head_idx'
				""", String.class)).contains("UNIQUE").contains("WHERE head");
		assertThat(jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
				.endsWith("31", "32", "33", "34", "35");
	}

	private static MaritimeSnapshot revision(String retrievedAt, String current) {
		return new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:" + MOTHER + ":SIN:SIN", "MPA OCEANS-X Vessels Due to Arrive",
				current, retrievedAt, retrievedAt, "LIVE", null, MOTHER, "Singapore", null, null, "2026-10-28 23:00:00",
				current, null, null, null);
	}
}
