package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentFilterServiceIntegrationTests {

	@Autowired ShipmentService shipments;
	@Autowired JdbcTemplate jdbc;

	private CompanyFixture forwarder;
	private CompanyFixture linkedImporter;
	private CompanyFixture otherImporter;
	private CompanyFixture unrelatedCompany;
	private AuthenticatedUser forwarderUser;
	private AuthenticatedUser linkedImporterUser;
	private AuthenticatedUser unrelatedUser;

	@BeforeEach
	void setUp() {
		forwarder = company("Forwarder Logistics");
		linkedImporter = company("Ocean_Trade% Limited");
		otherImporter = company("OceanXTradeZ Limited");
		unrelatedCompany = company("Unrelated Customer");
		forwarderUser = account(Role.FREIGHT_FORWARDER, forwarder.id());
		linkedImporterUser = account(Role.IMPORTER, linkedImporter.id());
		unrelatedUser = account(Role.IMPORTER, unrelatedCompany.id());
	}

	@Test
	void noCriteriaKeepsTheAuthorizedListNewestFirstAndBlankTextIsIgnored() {
		insert(forwarder, null, "HBL-OLDER", "MV Older", "MV Feeder", at("2026-10-12T08:00:00+08:00"),
				Instant.parse("2026-10-01T00:00:00Z"));
		insert(forwarder, linkedImporter, "HBL-NEWER", "MV Newer", "MV Feeder", at("2026-10-12T08:00:00+08:00"),
				Instant.parse("2026-10-02T00:00:00Z"));
		insert(unrelatedCompany, null, "HBL-HIDDEN", "MV Hidden", "MV Feeder", at("2026-10-12T08:00:00+08:00"),
				Instant.parse("2026-10-03T00:00:00Z"));

		assertThat(references(forwarderUser, ShipmentFilterCriteria.none()))
				.containsExactly("HBL-NEWER", "HBL-OLDER");
		assertThat(references(forwarderUser, ShipmentFilterCriteria.of("  ", "\t", " ", null)))
				.containsExactly("HBL-NEWER", "HBL-OLDER");
	}

	@Test
	void referenceSearchIsCaseInsensitiveAndTreatsPercentAndUnderscoreLiterally() {
		insert(forwarder, null, "HBL-Alpha_100%", "MV One", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, null, "HBL-AlphaX100Z", "MV Two", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());

		assertThat(references(forwarderUser, ShipmentFilterCriteria.of(" alpha_100% ", null, null, null)))
				.containsExactly("HBL-Alpha_100%");
	}

	@Test
	void vesselSearchMatchesEitherMotherOrFeederVessel() {
		insert(forwarder, null, "HBL-MOTHER", "MV Pacific Horizon", "MV Other", at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, null, "HBL-FEEDER", "MV Other", "MV Pacific Horizon", at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, null, "HBL-NO-MATCH", "MV PacificXHorizon", "MV Other", at("2026-10-12T08:00:00+08:00"), Instant.now());

		assertThat(references(forwarderUser, ShipmentFilterCriteria.of(null, "pacific horizon", null, null)))
				.containsExactlyInAnyOrder("HBL-MOTHER", "HBL-FEEDER");
	}

	@Test
	void customerSearchUsesOnlyTheLinkedImporterCodeOrNameWithLiteralSubstringMatching() {
		insert(forwarder, linkedImporter, "HBL-LINKED", "MV One", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, otherImporter, "HBL-OTHER-LINK", "MV Two", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, null, "HBL-UNLINKED", "MV Three", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());

		assertThat(references(forwarderUser, ShipmentFilterCriteria.of(null, null, linkedImporter.code().toLowerCase(), null)))
				.containsExactly("HBL-LINKED");
		assertThat(references(forwarderUser, ShipmentFilterCriteria.of(null, null, "ocean_trade%", null)))
				.containsExactly("HBL-LINKED");
		assertThat(references(forwarderUser, ShipmentFilterCriteria.of(null, null, forwarder.name(), null)))
				.isEmpty();
	}

	@Test
	void importerManagedShipmentsWithoutALinkDoNotMatchCustomerSearch() {
		AuthenticatedUser importerUser = account(Role.IMPORTER, linkedImporter.id());
		insert(linkedImporter, null, "HBL-IMPORTER-MANAGED", "MV One", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());

		assertThat(references(importerUser, ShipmentFilterCriteria.of(null, null, linkedImporter.name(), null))).isEmpty();
	}

	@Test
	void arrivalDateUsesInclusiveSingaporeStartAndExclusiveNextMidnight() {
		LocalDate date = LocalDate.of(2026, 10, 12);
		insert(forwarder, null, "HBL-START", "MV One", "MV Feeder", at("2026-10-11T16:00:00Z"), Instant.now());
		insert(forwarder, null, "HBL-BEFORE-END", "MV Two", "MV Feeder", at("2026-10-12T15:59:59.999999Z"), Instant.now());
		insert(forwarder, null, "HBL-NEXT-DAY", "MV Three", "MV Feeder", at("2026-10-12T16:00:00Z"), Instant.now());

		assertThat(references(forwarderUser, ShipmentFilterCriteria.of(null, null, null, date)))
				.containsExactlyInAnyOrder("HBL-START", "HBL-BEFORE-END");
	}

	@Test
	void combinesEveryPresentFilterWithAnd() {
		insert(forwarder, linkedImporter, "HBL-COMBINED", "MV Pacific Horizon", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, linkedImporter, "HBL-COMBINED-WRONG-VESSEL", "MV Other", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, otherImporter, "HBL-COMBINED-WRONG-CUSTOMER", "MV Pacific Horizon", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, linkedImporter, "HBL-COMBINED-WRONG-DATE", "MV Pacific Horizon", "MV Feeder", at("2026-10-13T08:00:00+08:00"), Instant.now());

		ShipmentFilterCriteria criteria = ShipmentFilterCriteria.of("combined", "pacific", linkedImporter.code(),
				LocalDate.of(2026, 10, 12));
		assertThat(references(forwarderUser, criteria)).containsExactly("HBL-COMBINED");
	}

	@Test
	void linkedImporterCanSearchVisibleLinkedShipmentsButUnrelatedCompanyCannot() {
		insert(forwarder, linkedImporter, "HBL-SHARED-SEARCH", "MV Shared", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, otherImporter, "HBL-OTHER-SEARCH", "MV Shared", "MV Feeder", at("2026-10-12T08:00:00+08:00"), Instant.now());

		ShipmentFilterCriteria criteria = ShipmentFilterCriteria.of("shared-search", null, null, null);
		assertThat(references(linkedImporterUser, criteria)).containsExactly("HBL-SHARED-SEARCH");
		assertThat(references(unrelatedUser, criteria)).isEmpty();
	}

	private List<String> references(AuthenticatedUser principal, ShipmentFilterCriteria criteria) {
		return shipments.list(principal, criteria).stream().map(ShipmentResponse::shipmentReference).toList();
	}

	private CompanyFixture company(String name) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
		String code = name.startsWith("Ocean_") ? "OCEAN_TRADE%" + suffix : "CDG131_" + suffix;
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, name);
		Long id = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
		return new CompanyFixture(id, code, name);
	}

	private AuthenticatedUser account(Role role, Long companyId) {
		String email = "cdg131-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-131 User', ?, 'unused', id, ? FROM roles WHERE code = ?
				""", email, companyId, role.name());
		Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		return new AuthenticatedUser(id, email, role, Instant.parse("2030-01-01T00:00:00Z"));
	}

	private void insert(CompanyFixture managingCompany, CompanyFixture importerCompany, String reference,
			String motherVessel, String feederVessel, OffsetDateTime arrival, Instant createdAt) {
		Long creatorId = jdbc.queryForObject(
				"SELECT id FROM users WHERE company_id = ? ORDER BY id LIMIT 1", Long.class, managingCompany.id());
		jdbc.update("""
				INSERT INTO shipments (
					company_id, importer_company_id, created_by_user_id, updated_by_user_id,
					shipment_reference, origin, destination, transshipment_port, mother_vessel,
					planned_mother_arrival_at, feeder_vessel, planned_feeder_departure_at,
					created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 'Singapore', 'Rotterdam', 'Tanjung Pelepas', ?, ?, ?, ?, ?, ?, 0)
				""", managingCompany.id(), importerCompany == null ? null : importerCompany.id(), creatorId,
				creatorId, reference, motherVessel, arrival, feederVessel, arrival.plusHours(8),
				Timestamp.from(createdAt), Timestamp.from(createdAt));
	}

	private static OffsetDateTime at(String value) {
		return OffsetDateTime.parse(value);
	}

	private record CompanyFixture(Long id, String code, String name) {
	}
}
