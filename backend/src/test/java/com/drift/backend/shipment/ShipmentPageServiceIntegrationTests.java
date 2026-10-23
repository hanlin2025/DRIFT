package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG168_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG168_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG168_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG168_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG168_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG168_DB_PASSWORD}",
		"app.ais.enabled=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG168_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG168_OWNERSHIP_TOKEN}"
})
@ActiveProfiles("test")
@ContextConfiguration(initializers = ShipmentPageDatabaseGuard.class)
@Transactional
class ShipmentPageServiceIntegrationTests {

	private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");
	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-10-12T08:00:00+08:00");

	@Autowired ShipmentService shipments;
	@Autowired JdbcTemplate jdbc;

	private CompanyFixture forwarder;
	private CompanyFixture linkedImporter;
	private CompanyFixture unrelatedCompany;
	private AuthenticatedUser forwarderUser;
	private AuthenticatedUser linkedImporterUser;
	private AuthenticatedUser unrelatedUser;

	@BeforeEach
	void setUp() {
		forwarder = company("Forwarder Logistics");
		linkedImporter = company("Linked Importer");
		unrelatedCompany = company("Unrelated Customer");
		forwarderUser = account(Role.FREIGHT_FORWARDER, forwarder.id());
		linkedImporterUser = account(Role.IMPORTER, linkedImporter.id());
		unrelatedUser = account(Role.IMPORTER, unrelatedCompany.id());
	}

	@Test
	void pagesTwentyFiveAuthorisedShipmentsAndKeepsTheRemainder() {
		List<String> references = new ArrayList<>();
		for (int index = 0; index < 26; index++) {
			String reference = "HBL-" + String.format("%02d", index);
			references.add(reference);
			insert(forwarder, null, reference, "MV Page", "MV Feeder", ARRIVAL.plusHours(index), CREATED.plusSeconds(index));
		}
		insert(unrelatedCompany, null, "HBL-HIDDEN", "MV Hidden", "MV Feeder", ARRIVAL, CREATED.plusSeconds(30));

		ShipmentPage first = page(forwarderUser, ShipmentFilterCriteria.none(), 1, ShipmentSort.CREATED_DATE_DESCENDING);
		ShipmentPage second = page(forwarderUser, ShipmentFilterCriteria.none(), 2, ShipmentSort.CREATED_DATE_DESCENDING);

		assertThat(first.page()).isEqualTo(1);
		assertThat(first.size()).isEqualTo(25);
		assertThat(first.total()).isEqualTo(26);
		assertThat(referencesOf(first)).containsExactlyElementsOf(reversed(references.subList(1, 26)));
		assertThat(second.page()).isEqualTo(2);
		assertThat(second.size()).isEqualTo(25);
		assertThat(second.total()).isEqualTo(26);
		assertThat(referencesOf(second)).containsExactly("HBL-00");
	}

	@Test
	void countsOnlyShipmentsTheCompanyIsAuthorisedToView() {
		insert(forwarder, null, "HBL-OWN", "MV Own", "MV Feeder", ARRIVAL, CREATED);
		insert(forwarder, linkedImporter, "HBL-LINKED", "MV Linked", "MV Feeder", ARRIVAL.plusHours(1), CREATED.plusSeconds(1));
		insert(unrelatedCompany, null, "HBL-HIDDEN", "MV Hidden", "MV Feeder", ARRIVAL, CREATED.plusSeconds(2));

		ShipmentPage forwarderPage = page(forwarderUser, ShipmentFilterCriteria.none(), 1, ShipmentSort.CREATED_DATE_DESCENDING);
		ShipmentPage importerPage = page(linkedImporterUser, ShipmentFilterCriteria.none(), 1, ShipmentSort.CREATED_DATE_DESCENDING);
		ShipmentPage unrelatedPage = page(unrelatedUser, ShipmentFilterCriteria.none(), 1, ShipmentSort.CREATED_DATE_DESCENDING);

		assertThat(forwarderPage.total()).isEqualTo(2);
		assertThat(referencesOf(forwarderPage)).containsExactly("HBL-LINKED", "HBL-OWN");
		assertThat(importerPage.total()).isEqualTo(1);
		assertThat(referencesOf(importerPage)).containsExactly("HBL-LINKED");
		assertThat(unrelatedPage.total()).isEqualTo(1);
		assertThat(referencesOf(unrelatedPage)).containsExactly("HBL-HIDDEN");
	}

	@Test
	void sortsPlannedArrivalReferenceAndCreatedDateInBothDirections() {
		insert(forwarder, null, "HBL-B", "MV B", "MV Feeder", ARRIVAL.plusHours(1), CREATED.plusSeconds(2));
		insert(forwarder, null, "HBL-A", "MV A", "MV Feeder", ARRIVAL.plusHours(2), CREATED.plusSeconds(0));
		insert(forwarder, null, "HBL-C", "MV C", "MV Feeder", ARRIVAL.plusHours(0), CREATED.plusSeconds(1));

		assertThat(referencesOf(page(forwarderUser, ShipmentFilterCriteria.none(), 1, sort(ShipmentSort.Field.PLANNED_ARRIVAL, ShipmentSort.Direction.ASC))))
				.containsExactly("HBL-C", "HBL-B", "HBL-A");
		assertThat(referencesOf(page(forwarderUser, ShipmentFilterCriteria.none(), 1, sort(ShipmentSort.Field.PLANNED_ARRIVAL, ShipmentSort.Direction.DESC))))
				.containsExactly("HBL-A", "HBL-B", "HBL-C");
		assertThat(referencesOf(page(forwarderUser, ShipmentFilterCriteria.none(), 1, sort(ShipmentSort.Field.REFERENCE, ShipmentSort.Direction.ASC))))
				.containsExactly("HBL-A", "HBL-B", "HBL-C");
		assertThat(referencesOf(page(forwarderUser, ShipmentFilterCriteria.none(), 1, sort(ShipmentSort.Field.REFERENCE, ShipmentSort.Direction.DESC))))
				.containsExactly("HBL-C", "HBL-B", "HBL-A");
		assertThat(referencesOf(page(forwarderUser, ShipmentFilterCriteria.none(), 1, sort(ShipmentSort.Field.CREATED_AT, ShipmentSort.Direction.ASC))))
				.containsExactly("HBL-A", "HBL-C", "HBL-B");
		assertThat(referencesOf(page(forwarderUser, ShipmentFilterCriteria.none(), 1, ShipmentSort.CREATED_DATE_DESCENDING)))
				.containsExactly("HBL-B", "HBL-C", "HBL-A");
	}

	@Test
	void appliesSortTogetherWithSearchAndKeepsArchivedShipmentsOutUnlessRequested() {
		insert(forwarder, linkedImporter, "HBL-KEEP-LATE", "MV Pacific", "MV Feeder", ARRIVAL.plusHours(5), CREATED);
		insert(forwarder, linkedImporter, "HBL-KEEP-EARLY", "MV Pacific", "MV Feeder", ARRIVAL.plusHours(1), CREATED.plusSeconds(1));
		insert(forwarder, linkedImporter, "HBL-OTHER", "MV Other", "MV Feeder", ARRIVAL, CREATED.plusSeconds(2));
		insert(forwarder, linkedImporter, "HBL-KEEP-ARCHIVED", "MV Pacific", "MV Feeder", ARRIVAL.plusHours(3), CREATED.plusSeconds(3));
		insert(unrelatedCompany, null, "HBL-KEEP-HIDDEN", "MV Pacific", "MV Feeder", ARRIVAL, CREATED);
		archive("HBL-KEEP-ARCHIVED");

		ShipmentFilterCriteria activePacific = ShipmentFilterCriteria.of("keep", "pacific", linkedImporter.code(), null);
		ShipmentPage active = page(forwarderUser, activePacific, 1, sort(ShipmentSort.Field.PLANNED_ARRIVAL, ShipmentSort.Direction.ASC));
		ShipmentFilterCriteria archived = ShipmentFilterCriteria.of("keep", null, null, null, ShipmentStatus.ARCHIVED);
		ShipmentPage archivedPage = page(forwarderUser, archived, 1, sort(ShipmentSort.Field.REFERENCE, ShipmentSort.Direction.ASC));

		assertThat(active.total()).isEqualTo(2);
		assertThat(referencesOf(active)).containsExactly("HBL-KEEP-EARLY", "HBL-KEEP-LATE");
		assertThat(archivedPage.total()).isEqualTo(1);
		assertThat(referencesOf(archivedPage)).containsExactly("HBL-KEEP-ARCHIVED");
		assertThat(page(unrelatedUser, activePacific, 1, ShipmentSort.CREATED_DATE_DESCENDING).total()).isZero();
	}

	@Test
	void outOfRangePageIsEmptyAndKeepsTheTotal() {
		insert(forwarder, null, "HBL-ONLY", "MV Only", "MV Feeder", ARRIVAL, CREATED);

		ShipmentPage beyond = page(forwarderUser, ShipmentFilterCriteria.none(), 3, ShipmentSort.CREATED_DATE_DESCENDING);
		ShipmentPage emptyBeyond = page(forwarderUser, ShipmentFilterCriteria.of("missing", null, null, null), 2,
				ShipmentSort.CREATED_DATE_DESCENDING);

		assertThat(beyond.page()).isEqualTo(3);
		assertThat(beyond.size()).isEqualTo(25);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.total()).isEqualTo(1);
		assertThat(emptyBeyond.page()).isEqualTo(2);
		assertThat(emptyBeyond.items()).isEmpty();
		assertThat(emptyBeyond.total()).isZero();
	}

	@Test
	void ordersEqualSortValuesByIdAcrossThePageBoundary() {
		for (int index = 0; index < 26; index++) {
			insert(forwarder, null, "HBL-" + String.format("%02d", index), "MV Tie", "MV Feeder", ARRIVAL, CREATED);
		}
		List<Long> ids = jdbc.queryForList("SELECT id FROM shipments WHERE company_id = ? ORDER BY id", Long.class, forwarder.id());

		ShipmentPage first = page(forwarderUser, ShipmentFilterCriteria.none(), 1, ShipmentSort.CREATED_DATE_DESCENDING);
		ShipmentPage second = page(forwarderUser, ShipmentFilterCriteria.none(), 2, ShipmentSort.CREATED_DATE_DESCENDING);

		assertThat(idsOf(first)).containsExactlyElementsOf(ids.subList(0, 25));
		assertThat(idsOf(second)).containsExactly(ids.get(25));
		assertThat(first.total()).isEqualTo(26);
	}

	@Test
	void breaksPlannedArrivalTiesByIdRatherThanCreatedDate() {
		insert(forwarder, null, "HBL-FIRST-ID", "MV Tie", "MV Feeder", ARRIVAL, CREATED.plusSeconds(5));
		insert(forwarder, null, "HBL-SECOND-ID", "MV Tie", "MV Feeder", ARRIVAL, CREATED);

		assertThat(referencesOf(page(forwarderUser, ShipmentFilterCriteria.none(), 1,
				sort(ShipmentSort.Field.PLANNED_ARRIVAL, ShipmentSort.Direction.ASC))))
				.containsExactly("HBL-FIRST-ID", "HBL-SECOND-ID");
	}

	@Test
	void existingListStillReturnsEveryAuthorisedShipmentNewestFirst() {
		insert(forwarder, null, "HBL-OLDER", "MV Older", "MV Feeder", ARRIVAL, CREATED);
		insert(forwarder, null, "HBL-NEWER", "MV Newer", "MV Feeder", ARRIVAL.plusHours(1), CREATED.plusSeconds(1));
		insert(unrelatedCompany, null, "HBL-HIDDEN", "MV Hidden", "MV Feeder", ARRIVAL, CREATED.plusSeconds(2));

		assertThat(shipments.list(forwarderUser).stream().map(ShipmentResponse::shipmentReference).toList())
				.containsExactly("HBL-NEWER", "HBL-OLDER");
	}

	private ShipmentPage page(AuthenticatedUser principal, ShipmentFilterCriteria criteria, int pageNumber, ShipmentSort sort) {
		return shipments.listPage(principal, criteria, pageNumber, sort);
	}

	private static ShipmentSort sort(ShipmentSort.Field field, ShipmentSort.Direction direction) {
		return new ShipmentSort(field, direction);
	}

	private static List<String> referencesOf(ShipmentPage page) {
		return page.items().stream().map(ShipmentResponse::shipmentReference).toList();
	}

	private static List<Long> idsOf(ShipmentPage page) {
		return page.items().stream().map(ShipmentResponse::id).toList();
	}

	private static List<String> reversed(List<String> values) {
		List<String> reversed = new ArrayList<>(values);
		Collections.reverse(reversed);
		return reversed;
	}

	private CompanyFixture company(String name) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
		String code = "CDG168_" + suffix;
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, name);
		Long id = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
		return new CompanyFixture(id, code, name);
	}

	private AuthenticatedUser account(Role role, Long companyId) {
		String email = "cdg168-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-168 User', ?, 'unused', id, ? FROM roles WHERE code = ?
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

	private void archive(String reference) {
		jdbc.update("UPDATE shipments SET status = 'ARCHIVED' WHERE shipment_reference = ?", reference);
	}

	private record CompanyFixture(Long id, String code, String name) {
	}
}
