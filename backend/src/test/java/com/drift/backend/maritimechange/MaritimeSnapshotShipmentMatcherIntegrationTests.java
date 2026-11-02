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
class MaritimeSnapshotShipmentMatcherIntegrationTests {

	private static final String MOTHER = "9351048";

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired JdbcTemplate jdbc;
	@Autowired MaritimeSnapshotChangeDetector detector;
	@Autowired MaritimeSnapshotShipmentMatcher matcher;

	private Shipment owned;
	private Shipment otherCompany;
	private Long ownerId;
	private Long importerId;
	private Long outsiderId;

	@BeforeEach
	void setUp() {
		Company owner = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		Company importer = companies.findByCode("STRAITS_FRESH_DEMO").orElseThrow();
		ownerId = owner.getId();
		importerId = importer.getId();
		String code = "CDG265" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-265 Other Company");
		outsiderId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
		AccountRole role = entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", Role.FREIGHT_FORWARDER).getSingleResult();
		UserAccount actor = new UserAccount("Match Actor", "match-" + UUID.randomUUID() + "@example.com", "hash", role, owner);
		Company outside = companies.findById(outsiderId).orElseThrow();
		UserAccount outsider = new UserAccount("Outside Actor", "match-" + UUID.randomUUID() + "@example.com", "hash", role, outside);
		entityManager.persist(actor);
		entityManager.persist(outsider);
		owned = shipment(owner, actor);
		otherCompany = shipment(outside, outsider);
		entityManager.persist(owned);
		entityManager.persist(otherCompany);
		entityManager.flush();
		jdbc.update("UPDATE shipments SET mother_vessel_imo = ?, feeder_vessel_imo = ?, importer_company_id = ? WHERE id = ?",
				MOTHER, "7654329", importerId, owned.getId());
		jdbc.update("UPDATE shipments SET mother_vessel_imo = ? WHERE id = ?", MOTHER, otherCompany.getId());
		entityManager.clear();
	}

	@Test
	void matchesOnlyAShipmentTheViewerCanSee() {
		MaritimeSnapshotChange change = detector.accept(List.of(revision("2026-10-26T15:46:03Z", "2026-10-29 02:00:00")))
				.changes().getFirst();
		Integer observations = jdbc.queryForObject("SELECT COUNT(*) FROM maritime_snapshot_observations", Integer.class);

		MaritimeShipmentMatch owner = matcher.match(ownerId, change);
		MaritimeShipmentMatch importer = matcher.match(importerId, change);
		MaritimeShipmentMatch outsider = matcher.match(outsiderId, change);

		assertThat(owner.candidates()).extracting(MaritimeShipmentCandidate::shipmentId).contains(owned.getId());
		assertThat(owner.candidates()).filteredOn(candidate -> candidate.shipmentId() == owned.getId()).singleElement()
				.satisfies(candidate -> {
					assertThat(candidate.owningCompanyId()).isEqualTo(ownerId);
					assertThat(candidate.association()).isEqualTo("UNVERIFIED_IMO_ONLY");
					assertThat(candidate.vesselSpecificImpact()).isFalse();
				});
		assertThat(owner.candidates()).extracting(MaritimeShipmentCandidate::shipmentId).doesNotContain(otherCompany.getId());
		assertThat(owner.exclusions()).extracting(MaritimeShipmentExclusion::shipmentId).doesNotContain(otherCompany.getId());
		assertThat(importer.candidates()).extracting(MaritimeShipmentCandidate::shipmentId).contains(owned.getId());
		assertThat(importer.candidates()).extracting(MaritimeShipmentCandidate::shipmentId).doesNotContain(otherCompany.getId());
		assertThat(outsider.candidates()).extracting(MaritimeShipmentCandidate::shipmentId).contains(otherCompany.getId());
		assertThat(outsider.candidates()).extracting(MaritimeShipmentCandidate::shipmentId).doesNotContain(owned.getId());
		assertThat(outsider.exclusions()).extracting(MaritimeShipmentExclusion::shipmentId).doesNotContain(owned.getId());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_snapshot_observations", Integer.class)).isEqualTo(observations);
	}

	@Test
	void anArchivedShipmentIsNotACandidate() {
		jdbc.update("UPDATE shipments SET status = 'ARCHIVED' WHERE id = ?", owned.getId());
		entityManager.clear();
		MaritimeSnapshotChange change = detector.accept(List.of(revision("2026-10-26T15:46:03Z", "2026-10-29 02:00:00")))
				.changes().getFirst();

		MaritimeShipmentMatch match = matcher.match(ownerId, change);

		assertThat(match.candidates()).extracting(MaritimeShipmentCandidate::shipmentId).doesNotContain(owned.getId());
		assertThat(match.exclusions()).extracting(MaritimeShipmentExclusion::shipmentId).doesNotContain(owned.getId());
	}

	private static Shipment shipment(Company company, UserAccount actor) {
		return new Shipment(company, actor, "CDG265-" + UUID.randomUUID(), "Jakarta", "Rotterdam", "Singapore",
				"KOTA LARIS", OffsetDateTime.parse("2026-10-28T08:00:00Z"), "FEEDER",
				OffsetDateTime.parse("2026-10-28T20:00:00Z"));
	}

	private static MaritimeSnapshot revision(String retrievedAt, String current) {
		return new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:" + MOTHER + ":SIN:SIN", "MPA OCEANS-X Vessels Due to Arrive",
				current, retrievedAt, retrievedAt, "LIVE", null, MOTHER, null, null, null, "2026-10-28 23:00:00", current,
				null, null, null);
	}
}
