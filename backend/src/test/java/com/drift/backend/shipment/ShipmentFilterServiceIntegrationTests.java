package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG81_FILTER_SERVICE_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG81_FILTER_SERVICE_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG81_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG81_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG81_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG81_DB_PASSWORD}",
		"app.ais.enabled=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG81_FILTER_SERVICE_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG81_FILTER_SERVICE_OWNERSHIP_TOKEN}"
})
@ActiveProfiles("test")
@ContextConfiguration(initializers = ShipmentDisposableDatabaseGuard.class)
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
	void listedShipmentsCarryTheirRiskAndLinkedImporterLikeTheDetail() {
		insert(forwarder, linkedImporter, "HBL-LINKED", "MV One", "MV Feeder", at("2026-10-12T08:00:00+08:00"),
				Instant.parse("2026-10-02T00:00:00Z"));
		insert(forwarder, null, "HBL-UNLINKED", "MV Two", "MV Feeder", at("2026-10-12T08:00:00+08:00"),
				Instant.parse("2026-10-01T00:00:00Z"));

		List<ShipmentResponse> listed = shipments.list(forwarderUser, ShipmentFilterCriteria.none());

		assertThat(listed).extracting(ShipmentResponse::shipmentReference).containsExactly("HBL-LINKED", "HBL-UNLINKED");
		ShipmentResponse linked = listed.get(0);
		assertThat(linked.importer().id()).isEqualTo(linkedImporter.id());
		assertThat(linked.importer().code()).isEqualTo(linkedImporter.code());
		assertThat(linked.importer().name()).isEqualTo(linkedImporter.name());
		assertThat(linked.risk().level()).isEqualTo(ShipmentRisk.Level.HIGH);
		ShipmentDetailResponse detail = shipments.get(forwarderUser, linked.id());
		assertThat(linked.risk()).isEqualTo(detail.risk());
		assertThat(linked.priority().score()).isEqualTo(detail.priority().score());
		assertThat(listed.get(1).importer()).isNull();
	}

	@Test
	void aFreshAisEstimateRaisesTheListedRiskExactlyAsOnTheDetail() {
		OffsetDateTime arrival = OffsetDateTime.now(ZoneOffset.UTC).plusHours(24);
		insert(forwarder, linkedImporter, "HBL-AIS-LATE", "MV Late Mother", "MV Feeder", arrival,
				Instant.parse("2026-10-02T00:00:00Z"), 16);
		insert(forwarder, linkedImporter, "HBL-PLANNED", "MV Unseen Mother", "MV Feeder", arrival,
				Instant.parse("2026-10-01T00:00:00Z"), 16);
		observeLateMother();

		List<ShipmentResponse> listed = shipments.list(forwarderUser, ShipmentFilterCriteria.none());

		ShipmentResponse late = listed.get(0);
		ShipmentResponse planned = listed.get(1);
		assertThat(late.shipmentReference()).isEqualTo("HBL-AIS-LATE");
		assertThat(late.risk().level()).isEqualTo(ShipmentRisk.Level.CRITICAL);
		assertThat(planned.risk().level()).isEqualTo(ShipmentRisk.Level.MEDIUM);
		ShipmentDetailResponse lateDetail = shipments.get(forwarderUser, late.id());
		assertThat(lateDetail.remainingConnectionWindow().status()).isEqualTo(RemainingConnectionWindow.Status.MISSED);
		assertThat(late.risk()).isEqualTo(lateDetail.risk());
		assertThat(late.priority().score()).isEqualTo(lateDetail.priority().score());
		assertThat(late.priority().confidence()).isEqualTo(ShipmentPriority.LIVE_CONFIDENCE);
		assertThat(late.priority().score()).isGreaterThan(planned.priority().score());
		assertThat(planned.risk()).isEqualTo(shipments.get(forwarderUser, planned.id()).risk());
	}

	@Test
	void riskSortOrdersCriticalToLowWithTheSoonestFeederDepartureBreakingTies() {
		OffsetDateTime arrival = at("2026-10-12T08:00:00+08:00");
		insert(forwarder, null, "HBL-LOW", "MV A", "MV F", arrival, Instant.parse("2026-10-07T00:00:00Z"), 48);
		insert(forwarder, null, "HBL-MEDIUM", "MV A", "MV F", arrival, Instant.parse("2026-10-02T00:00:00Z"), 18);
		insert(forwarder, null, "HBL-HIGH-LATE", "MV A", "MV F", arrival.plusDays(1), Instant.parse("2026-10-03T00:00:00Z"), 8);
		insert(forwarder, null, "HBL-HIGH-EARLY", "MV A", "MV F", arrival, Instant.parse("2026-10-04T00:00:00Z"), 8);
		insert(forwarder, null, "HBL-CRITICAL", "MV Late Mother", "MV F", arrival.plusDays(5),
				Instant.parse("2026-10-05T00:00:00Z"), 30);
		insert(unrelatedCompany, null, "HBL-HIDDEN", "MV Late Mother", "MV F", arrival,
				Instant.parse("2026-10-06T00:00:00Z"), 30);
		observeLateMother();

		assertThat(references(forwarderUser, ShipmentFilterCriteria.none(), ShipmentSort.RISK_FIRST))
				.containsExactly("HBL-CRITICAL", "HBL-HIGH-EARLY", "HBL-HIGH-LATE", "HBL-MEDIUM", "HBL-LOW");
		assertThat(references(forwarderUser, ShipmentFilterCriteria.none()))
				.containsExactly("HBL-LOW", "HBL-CRITICAL", "HBL-HIGH-EARLY", "HBL-HIGH-LATE", "HBL-MEDIUM");
	}

	@Test
	void prioritySortLetsAnImminentMediumConnectionOutrankAHighOneThatLeavesNextWeek() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		insert(forwarder, null, "HBL-HIGH-SOON", "MV A", "MV F", now.plusHours(2), Instant.parse("2026-10-01T00:00:00Z"), 8);
		insert(forwarder, null, "HBL-HIGH-NEXT-WEEK", "MV A", "MV F", now.plusDays(6), Instant.parse("2026-10-02T00:00:00Z"), 8);
		insert(forwarder, null, "HBL-MEDIUM-SOON", "MV A", "MV F", now.plusHours(2), Instant.parse("2026-10-03T00:00:00Z"), 18);

		List<ShipmentResponse> byPriority = shipments.list(forwarderUser, ShipmentFilterCriteria.none(),
				ShipmentSort.PRIORITY_FIRST);

		assertThat(byPriority).extracting(ShipmentResponse::shipmentReference)
				.containsExactly("HBL-HIGH-SOON", "HBL-MEDIUM-SOON", "HBL-HIGH-NEXT-WEEK");
		assertThat(byPriority).extracting(row -> row.priority().score()).isSortedAccordingTo(
				java.util.Comparator.reverseOrder());
		assertThat(references(forwarderUser, ShipmentFilterCriteria.none(), ShipmentSort.RISK_FIRST))
				.containsExactly("HBL-HIGH-SOON", "HBL-HIGH-NEXT-WEEK", "HBL-MEDIUM-SOON");
	}

	@Test
	void atRiskKeepsOnlyHighAndCriticalAndCombinesWithOtherFilters() {
		OffsetDateTime arrival = at("2026-10-12T08:00:00+08:00");
		insert(forwarder, linkedImporter, "HBL-HIGH", "MV A", "MV F", arrival, Instant.parse("2026-10-01T00:00:00Z"), 8);
		insert(forwarder, linkedImporter, "HBL-CRITICAL", "MV Late Mother", "MV F", arrival,
				Instant.parse("2026-10-02T00:00:00Z"), 30);
		insert(forwarder, linkedImporter, "HBL-MEDIUM", "MV A", "MV F", arrival, Instant.parse("2026-10-03T00:00:00Z"), 18);
		insert(forwarder, null, "HBL-OTHER-CUSTOMER", "MV A", "MV F", arrival, Instant.parse("2026-10-04T00:00:00Z"), 8);
		insert(forwarder, linkedImporter, "HBL-ARCHIVED", "MV A", "MV F", arrival, Instant.parse("2026-10-05T00:00:00Z"), 8);
		archive("HBL-ARCHIVED");
		observeLateMother();

		assertThat(references(forwarderUser, ShipmentFilterCriteria.of(null, null, null, null, null, true)))
				.containsExactly("HBL-OTHER-CUSTOMER", "HBL-CRITICAL", "HBL-HIGH");
		assertThat(references(forwarderUser, ShipmentFilterCriteria.of(null, null, "Ocean_Trade", null,
				ShipmentStatus.ACTIVE, true), ShipmentSort.RISK_FIRST))
				.containsExactly("HBL-CRITICAL", "HBL-HIGH");
		assertThat(references(forwarderUser, ShipmentFilterCriteria.of(null, null, null, null,
				ShipmentStatus.ARCHIVED, true)))
				.containsExactly("HBL-ARCHIVED");
	}

	@Test
	void aRankedPageIsCutAfterRankingTheWholeAuthorisedPortfolio() {
		OffsetDateTime arrival = at("2026-10-12T08:00:00+08:00");
		for (int i = 0; i < ShipmentService.PAGE_SIZE; i++) {
			insert(forwarder, null, "HBL-MEDIUM-%02d".formatted(i), "MV A", "MV F", arrival,
					Instant.parse("2026-10-01T00:00:00Z").plusSeconds(i), 18);
		}
		insert(forwarder, null, "HBL-LOW-NEWEST", "MV A", "MV F", arrival, Instant.parse("2026-10-09T00:00:00Z"), 48);
		insert(forwarder, null, "HBL-HIGH-OLDEST", "MV A", "MV F", arrival, Instant.parse("2026-09-01T00:00:00Z"), 8);
		insert(unrelatedCompany, null, "HBL-HIDDEN", "MV A", "MV F", arrival, Instant.parse("2026-10-09T00:00:00Z"), 8);

		ShipmentPage first = shipments.listPage(forwarderUser, ShipmentFilterCriteria.none(), 1, ShipmentSort.RISK_FIRST);
		ShipmentPage second = shipments.listPage(forwarderUser, ShipmentFilterCriteria.none(), 2, ShipmentSort.RISK_FIRST);
		ShipmentPage atRisk = shipments.listPage(forwarderUser, ShipmentFilterCriteria.of(null, null, null, null, null, true),
				1, ShipmentSort.CREATED_DATE_DESCENDING);

		assertThat(first.total()).isEqualTo(ShipmentService.PAGE_SIZE + 2);
		assertThat(first.items()).hasSize(ShipmentService.PAGE_SIZE);
		assertThat(first.items().get(0).shipmentReference()).isEqualTo("HBL-HIGH-OLDEST");
		assertThat(second.items()).extracting(ShipmentResponse::shipmentReference).containsExactly("HBL-LOW-NEWEST");
		assertThat(atRisk.total()).isEqualTo(1);
		assertThat(atRisk.items()).extracting(ShipmentResponse::shipmentReference).containsExactly("HBL-HIGH-OLDEST");
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

	@Test
	void statusFilterUsesActiveByDefaultAndLetsAuthorizedUsersExplicitlySearchArchivedShipments() {
		insert(forwarder, linkedImporter, "HBL-ACTIVE-STATUS", "MV Pacific Horizon", "MV Feeder",
				at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(forwarder, linkedImporter, "HBL-ARCHIVED-STATUS", "MV Pacific Horizon", "MV Feeder",
				at("2026-10-12T08:00:00+08:00"), Instant.now());
		insert(unrelatedCompany, null, "HBL-HIDDEN-ARCHIVED", "MV Pacific Horizon", "MV Feeder",
				at("2026-10-12T08:00:00+08:00"), Instant.now());
		archive("HBL-ARCHIVED-STATUS");
		archive("HBL-HIDDEN-ARCHIVED");

		assertThat(references(forwarderUser, ShipmentFilterCriteria.none())).containsExactly("HBL-ACTIVE-STATUS");
		assertThat(references(forwarderUser,
				ShipmentFilterCriteria.of(null, null, null, null, ShipmentStatus.ACTIVE)))
				.containsExactly("HBL-ACTIVE-STATUS");
		ShipmentFilterCriteria archivedPacific = ShipmentFilterCriteria.of("archived", "pacific",
				linkedImporter.code(), LocalDate.of(2026, 10, 12), ShipmentStatus.ARCHIVED);
		assertThat(references(forwarderUser, archivedPacific)).containsExactly("HBL-ARCHIVED-STATUS");
		assertThat(references(linkedImporterUser,
				ShipmentFilterCriteria.of(null, null, null, null, ShipmentStatus.ARCHIVED)))
				.containsExactly("HBL-ARCHIVED-STATUS");
		assertThat(references(unrelatedUser,
				ShipmentFilterCriteria.of(null, null, null, null, ShipmentStatus.ARCHIVED))).isEmpty();
	}

	private List<String> references(AuthenticatedUser principal, ShipmentFilterCriteria criteria) {
		return references(principal, criteria, ShipmentSort.CREATED_DATE_DESCENDING);
	}

	private List<String> references(AuthenticatedUser principal, ShipmentFilterCriteria criteria, ShipmentSort sort) {
		return shipments.list(principal, criteria, sort).stream().map(ShipmentResponse::shipmentReference).toList();
	}

	/**
	 * The schedule check stops a stored feeder departing at or before the mother arrives, so CRITICAL comes from a
	 * fresh AIS position whose estimate lands after the feeder has left.
	 */
	private void observeLateMother() {
		observe("563000188", "Late Mother", "8.500000001", "100.250000001", "5.0", Instant.now());
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
		insert(managingCompany, importerCompany, reference, motherVessel, feederVessel, arrival, createdAt, 8);
	}

	private void insert(CompanyFixture managingCompany, CompanyFixture importerCompany, String reference,
			String motherVessel, String feederVessel, OffsetDateTime arrival, Instant createdAt, int feederGapHours) {
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
				creatorId, reference, motherVessel, arrival, feederVessel, arrival.plusHours(feederGapHours),
				Timestamp.from(createdAt), Timestamp.from(createdAt));
	}

	private void observe(String mmsi, String vesselName, String latitude, String longitude, String speed, Instant ingestedAt) {
		jdbc.update("""
				INSERT INTO vessel_observations (
					mmsi, vessel_name, latitude, longitude, speed_over_ground_knots, ingested_at, source)
				VALUES (?, ?, ?, ?, ?, ?::timestamptz, 'SEEDED')
				""", mmsi, vesselName, new BigDecimal(latitude), new BigDecimal(longitude), new BigDecimal(speed),
				ingestedAt.toString());
	}

	private static OffsetDateTime at(String value) {
		return OffsetDateTime.parse(value);
	}

	private void archive(String reference) {
		jdbc.update("UPDATE shipments SET status = 'ARCHIVED' WHERE shipment_reference = ?", reference);
	}

	private record CompanyFixture(Long id, String code, String name) {
	}
}
