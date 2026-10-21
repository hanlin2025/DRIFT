package com.drift.backend.shipment.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.CreateShipmentRequest;
import com.drift.backend.shipment.Shipment;
import com.drift.backend.shipment.ShipmentLinkService;
import com.drift.backend.shipment.ShipmentService;
import com.drift.backend.shipment.UpdateShipmentRequest;
import com.drift.backend.shipment.csvimport.ShipmentImportRow;
import com.drift.backend.shipment.exception.ShipmentAlreadyArchivedException;
import com.drift.backend.shipment.exception.StaleShipmentVersionException;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentHistoryRecordingIntegrationTests {

	private static final String MOTHER_ARRIVAL = "2026-10-15T08:00:00+08:00";
	private static final String FEEDER_DEPARTURE = "2026-10-16T12:00:00+08:00";

	@Autowired ShipmentService shipments;
	@Autowired ShipmentLinkService links;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;

	private Long companyId;
	private Long importerCompanyId;
	private AuthenticatedUser forwarder;

	@BeforeEach
	void setUp() {
		companyId = companyId("HARBOURLINE_DEMO");
		importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		forwarder = account("forwarder", Role.FREIGHT_FORWARDER, companyId);
	}

	@Test
	void creatingAShipmentRecordsItsCreationByTheCreator() {
		Long shipmentId = create("HBL-CDG181-CREATE");
		entityManager.clear();
		Instant createdAt = shipment(shipmentId).getCreatedAt();

		assertThat(events(shipmentId)).singleElement().satisfies(event -> {
			assertThat(event.get("change_type")).isEqualTo("CREATED");
			assertThat(event.get("actor_user_id")).isEqualTo(forwarder.id());
			assertThat(event.get("earlier_changes_unrecorded")).isEqualTo(false);
			assertThat(((java.sql.Timestamp) event.get("recorded_at")).toInstant())
					.isEqualTo(createdAt);
		});
		assertThat(fieldChanges(shipmentId)).isEmpty();
	}

	@Test
	void importingAShipmentRecordsItsCreation() {
		// The import job's own creator commits in a separate transaction, so call the creation it delegates to.
		shipments.createImported(entityManager.find(Company.class, companyId),
				entityManager.find(UserAccount.class, forwarder.id()), new ShipmentImportRow(2, "HBL-CDG181-CSV",
						"Shanghai, CN", "Jakarta, ID", "Singapore", "MV Pacific Horizon",
						OffsetDateTime.parse(MOTHER_ARRIVAL), "MV Strait Runner", OffsetDateTime.parse(FEEDER_DEPARTURE),
						null), null);
		Long shipmentId = jdbc.queryForObject("SELECT id FROM shipments WHERE shipment_reference = ? AND company_id = ?",
				Long.class, "HBL-CDG181-CSV", companyId);

		assertThat(events(shipmentId)).extracting(event -> event.get("change_type")).containsExactly("CREATED");
	}

	@Test
	void editingRecordsOnlyTheChangedFieldsWithTheirPreviousAndNewValues() {
		Long shipmentId = create("HBL-CDG181-EDIT");
		AuthenticatedUser editor = account("editor", Role.IMPORTER, companyId);

		shipments.update(editor, shipmentId, request("HBL-CDG181-EDIT", version(shipmentId), "Busan, KR",
				"MV Strait Runner", "2026-10-17T09:30:00+08:00"));

		assertThat(events(shipmentId)).extracting(event -> event.get("change_type"), event -> event.get("actor_user_id"))
				.containsExactly(tuple("CREATED", forwarder.id()), tuple("UPDATED", editor.id()));
		assertThat(fieldChanges(shipmentId)).containsExactly(
				List.of("origin", "Shanghai, CN", "Busan, KR"),
				List.of("plannedFeederDepartureAt", "2026-10-16T04:00:00Z", "2026-10-17T01:30:00Z"));
	}

	@Test
	void editingRecordsTheSameTimeAsTheShipmentsLastUpdate() {
		Long shipmentId = create("HBL-CDG181-TIME");

		shipments.update(forwarder, shipmentId, request("HBL-CDG181-TIME", version(shipmentId), "Busan, KR",
				"MV Strait Runner", FEEDER_DEPARTURE));
		entityManager.clear();

		Instant updatedAt = shipment(shipmentId).getUpdatedAt();
		assertThat(jdbc.queryForObject("""
				SELECT recorded_at FROM shipment_change_events WHERE shipment_id = ? AND change_type = 'UPDATED'
				""", java.sql.Timestamp.class, shipmentId).toInstant()).isEqualTo(updatedAt);
	}

	@Test
	void anEditThatChangesNothingWritesNoEntry() {
		Long shipmentId = create("HBL-CDG181-SAME");

		// Same moment as the stored departure, sent in UTC instead of +08:00.
		shipments.update(forwarder, shipmentId, request("HBL-CDG181-SAME", version(shipmentId), "Shanghai, CN",
				"MV Strait Runner", "2026-10-16T04:00:00Z"));

		assertThat(events(shipmentId)).extracting(event -> event.get("change_type")).containsExactly("CREATED");
	}

	@Test
	void editingVesselIdentityRecordsTheIdentityFields() {
		Long shipmentId = create("HBL-CDG181-IDENTITY");
		Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
				"shipmentReference", "HBL-CDG181-IDENTITY",
				"origin", "Shanghai, CN",
				"destination", "Jakarta, ID",
				"transshipmentPort", "Singapore",
				"motherVessel", "MV Pacific Horizon",
				"plannedMotherArrivalAt", MOTHER_ARRIVAL,
				"feederVessel", "MV Strait Runner",
				"plannedFeederDepartureAt", FEEDER_DEPARTURE,
				"motherVesselImo", "9811000",
				"version", version(shipmentId)));

		shipments.update(forwarder, shipmentId, objectMapper.valueToTree(body));

		assertThat(fieldChanges(shipmentId)).containsExactly(List.of("motherVesselImo", "", "9811000"));
	}

	@Test
	void aRejectedEditWritesNoEntry() {
		Long shipmentId = create("HBL-CDG181-STALE");
		Long staleVersion = version(shipmentId);
		shipments.update(forwarder, shipmentId, request("HBL-CDG181-STALE", staleVersion, "Busan, KR",
				"MV Strait Runner", FEEDER_DEPARTURE));
		entityManager.clear();

		assertThatThrownBy(() -> shipments.update(forwarder, shipmentId, request("HBL-CDG181-STALE", staleVersion,
				"Ningbo, CN", "MV Strait Runner", FEEDER_DEPARTURE)))
				.isInstanceOf(StaleShipmentVersionException.class);

		assertThat(events(shipmentId)).extracting(event -> event.get("change_type"))
				.containsExactly("CREATED", "UPDATED");
	}

	@Test
	void linkingAndReplacingTheImporterRecordsTheCompanyNames() {
		Long shipmentId = create("HBL-CDG181-LINK");
		String importerName = companyName(importerCompanyId);

		links.link(forwarder, shipmentId, importerCompanyId);
		links.link(forwarder, shipmentId, importerCompanyId);

		assertThat(events(shipmentId)).extracting(event -> event.get("change_type"))
				.containsExactly("CREATED", "IMPORTER_LINKED");
		assertThat(fieldChanges(shipmentId)).containsExactly(List.of("importer", "", importerName));
	}

	@Test
	void archivingRecordsTheStatusChange() {
		Long shipmentId = create("HBL-CDG181-ARCHIVE");
		AuthenticatedUser manager = account("manager", Role.LOGISTICS_MANAGER, companyId);

		shipments.archive(manager, shipmentId);
		assertThatThrownBy(() -> shipments.archive(manager, shipmentId))
				.isInstanceOf(ShipmentAlreadyArchivedException.class);

		assertThat(events(shipmentId)).extracting(event -> event.get("change_type"), event -> event.get("actor_user_id"))
				.containsExactly(tuple("CREATED", forwarder.id()), tuple("ARCHIVED", manager.id()));
		assertThat(fieldChanges(shipmentId)).containsExactly(List.of("status", "ACTIVE", "ARCHIVED"));
	}

	private Long create(String reference) {
		return shipments.create(forwarder, new CreateShipmentRequest(reference, "Shanghai, CN", "Jakarta, ID",
				"Singapore", "MV Pacific Horizon", OffsetDateTime.parse(MOTHER_ARRIVAL), "MV Strait Runner",
				OffsetDateTime.parse(FEEDER_DEPARTURE), null, null, null, null, null, null)).id();
	}

	private UpdateShipmentRequest request(String reference, Long version, String origin, String feederVessel,
			String feederDeparture) {
		return new UpdateShipmentRequest(reference, origin, "Jakarta, ID", "Singapore", "MV Pacific Horizon",
				OffsetDateTime.parse(MOTHER_ARRIVAL), feederVessel, OffsetDateTime.parse(feederDeparture),
				null, null, null, null, null, null, version);
	}

	private List<Map<String, Object>> events(Long shipmentId) {
		return jdbc.queryForList("""
				SELECT change_type, actor_user_id, earlier_changes_unrecorded, recorded_at
				FROM shipment_change_events WHERE shipment_id = ? ORDER BY id
				""", shipmentId);
	}

	/** Field changes as [field, previous, new], with null written as "" so the lists stay comparable. */
	private List<List<String>> fieldChanges(Long shipmentId) {
		return jdbc.query("""
				SELECT f.field_name, COALESCE(f.previous_value, ''), COALESCE(f.new_value, '')
				FROM shipment_field_changes f JOIN shipment_change_events e ON e.id = f.event_id
				WHERE e.shipment_id = ? ORDER BY e.id, f.id
				""", (row, index) -> List.of(row.getString(1), row.getString(2), row.getString(3)), shipmentId);
	}

	private Shipment shipment(Long shipmentId) {
		return entityManager.find(Shipment.class, shipmentId);
	}

	private Long version(Long shipmentId) {
		entityManager.clear();
		return shipment(shipmentId).getVersion();
	}

	private AuthenticatedUser account(String prefix, Role role, Long assignedCompanyId) {
		String email = "cdg181-" + prefix + "-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 181 User', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), assignedCompanyId, role.name());
		Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		return new AuthenticatedUser(id, email, role, Instant.now().plusSeconds(3600));
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private String companyName(Long id) {
		return jdbc.queryForObject("SELECT name FROM companies WHERE id = ?", String.class, id);
	}
}
