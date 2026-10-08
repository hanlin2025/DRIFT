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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;
import com.drift.backend.shipment.exception.InvalidItineraryException;
import com.drift.backend.shipment.exception.InvalidShipmentRequestException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.drift.backend.shipment.exception.StaleShipmentVersionException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentUpdateServiceIntegrationTests {

	@Autowired ShipmentService shipments;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired jakarta.persistence.EntityManager entityManager;

	private AuthenticatedUser forwarder;
	private AuthenticatedUser importer;
	private Long companyId;

	@BeforeEach
	void setUp() {
		companyId = companyId("HARBOURLINE_DEMO");
		forwarder = account("forwarder", Role.FREIGHT_FORWARDER, companyId);
		importer = account("importer", Role.IMPORTER, companyId);
	}

	@Test
	void importerCanReplaceAllEditableFieldsAndUpdatesAuditAndVersion() {
		Long shipmentId = create(forwarder, "HBL-UPDATE-ALL");
		entityManager.clear();
		Shipment before = shipment(shipmentId);
		Instant createdAt = before.getCreatedAt();
		Long previousVersion = before.getVersion();

		ShipmentResponse response = shipments.update(importer, shipmentId, request("HBL-UPDATED", previousVersion,
				"Busan, KR", "Surabaya, ID", "Tanjung Pelepas", "MV New Mother",
				"2026-11-15T08:00:00+08:00", "MV New Feeder", "2026-11-16T12:00:00+08:00"));
		entityManager.clear();
		Shipment updated = shipment(shipmentId);

		assertThat(response.shipmentReference()).isEqualTo("HBL-UPDATED");
		assertThat(updated.getOrigin()).isEqualTo("Busan, KR");
		assertThat(updated.getDestination()).isEqualTo("Surabaya, ID");
		assertThat(updated.getTransshipmentPort()).isEqualTo("Tanjung Pelepas");
		assertThat(updated.getMotherVessel()).isEqualTo("MV New Mother");
		assertThat(updated.getFeederVessel()).isEqualTo("MV New Feeder");
		assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
		assertThat(updated.getUpdatedAt()).isAfter(createdAt);
		assertThat(updated.getUpdatedBy().getId()).isEqualTo(importer.id());
		assertThat(updated.getVersion()).isEqualTo(previousVersion + 1);
	}

	@Test
	void freightForwarderCanRetainItsExistingReferenceAndThenUseANewOne() {
		Long shipmentId = create(forwarder, "HBL-KEEP-REFERENCE");
		Shipment original = shipment(shipmentId);

		shipments.update(forwarder, shipmentId, request("  HBL-KEEP-REFERENCE  ", original.getVersion()));
		entityManager.clear();
		Shipment retained = shipment(shipmentId);
		assertThat(retained.getShipmentReference()).isEqualTo("HBL-KEEP-REFERENCE");

		shipments.update(forwarder, shipmentId, request("HBL-NEW-REFERENCE", retained.getVersion()));
		entityManager.clear();
		assertThat(shipment(shipmentId).getShipmentReference()).isEqualTo("HBL-NEW-REFERENCE");
	}

	@Test
	void rejectsAReferenceUsedByAnotherShipmentInTheSameCompany() {
		Long shipmentId = create(forwarder, "HBL-FIRST");
		create(forwarder, "HBL-SECOND");

		assertThatThrownBy(() -> shipments.update(forwarder, shipmentId,
				request("hbl-second", shipment(shipmentId).getVersion())))
				.isInstanceOf(DuplicateShipmentReferenceException.class);
	}

	@Test
	void rejectsAnInvalidItineraryWithoutChangingTheShipment() {
		Long shipmentId = create(forwarder, "HBL-BAD-TIMING");
		Shipment before = shipment(shipmentId);

		assertThatThrownBy(() -> shipments.update(forwarder, shipmentId, request("HBL-BAD-TIMING", before.getVersion(),
				"Shanghai, CN", "Jakarta, ID", "Singapore", "MV Pacific Horizon",
				"2026-10-16T12:00:00+08:00", "MV Strait Runner", "2026-10-15T08:00:00+08:00")))
				.isInstanceOf(InvalidItineraryException.class);
		entityManager.clear();
		assertThat(shipment(shipmentId).getVersion()).isEqualTo(before.getVersion());
	}

	@Test
	void rejectsMissingRequiredFieldsAndAnInvalidVersion() {
		Long shipmentId = create(forwarder, "HBL-MISSING");

		assertThatThrownBy(() -> shipments.update(forwarder, shipmentId,
				new UpdateShipmentRequest("", null, "", "", "", null, "", null, -1L)))
				.isInstanceOfSatisfying(InvalidShipmentRequestException.class, exception ->
						assertThat(exception.getErrors()).containsKeys("shipmentReference", "origin", "destination",
								"transshipmentPort", "motherVessel", "plannedMotherArrivalAt", "feederVessel",
								"plannedFeederDepartureAt", "version"));
	}

	@Test
	void hidesAnotherCompanysShipmentFromUpdate() {
		Long shipmentId = create(forwarder, "HBL-PRIVATE");
		AuthenticatedUser otherCompany = account("other", Role.IMPORTER, companyId("STRAITS_FRESH_DEMO"));

		assertThatThrownBy(() -> shipments.update(otherCompany, shipmentId,
				request("HBL-PRIVATE", shipment(shipmentId).getVersion())))
				.isInstanceOf(ShipmentNotFoundException.class);
	}

	@Test
	void rejectsAStaleVersionWithoutOverwritingTheNewerUpdate() {
		Long shipmentId = create(forwarder, "HBL-STALE");
		Long originalVersion = shipment(shipmentId).getVersion();

		shipments.update(forwarder, shipmentId, request("HBL-CURRENT", originalVersion));
		entityManager.clear();

		assertThatThrownBy(() -> shipments.update(importer, shipmentId, request("HBL-STALE-WRITE", originalVersion)))
				.isInstanceOf(StaleShipmentVersionException.class);
		entityManager.clear();
		Shipment current = shipment(shipmentId);
		assertThat(current.getShipmentReference()).isEqualTo("HBL-CURRENT");
		assertThat(current.getVersion()).isEqualTo(originalVersion + 1);
	}

	private Long create(AuthenticatedUser principal, String reference) {
		return shipments.create(principal, new CreateShipmentRequest(reference, "Shanghai, CN", "Jakarta, ID", "Singapore",
				"MV Pacific Horizon", OffsetDateTime.parse("2026-10-15T08:00:00+08:00"), "MV Strait Runner",
				OffsetDateTime.parse("2026-10-16T12:00:00+08:00"))).id();
	}

	private Shipment shipment(Long shipmentId) {
		return entityManager.find(Shipment.class, shipmentId);
	}

	private UpdateShipmentRequest request(String reference, Long version) {
		return request(reference, version, "Shanghai, CN", "Jakarta, ID", "Singapore", "MV Pacific Horizon",
				"2026-10-15T08:00:00+08:00", "MV Strait Runner", "2026-10-16T12:00:00+08:00");
	}

	private UpdateShipmentRequest request(String reference, Long version, String origin, String destination,
			String transshipmentPort, String motherVessel, String motherArrival, String feederVessel, String feederDeparture) {
		return new UpdateShipmentRequest(reference, origin, destination, transshipmentPort, motherVessel,
				OffsetDateTime.parse(motherArrival), feederVessel, OffsetDateTime.parse(feederDeparture), version);
	}

	private AuthenticatedUser account(String prefix, Role role, Long assignedCompanyId) {
		String email = "cdg86-" + prefix + "-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role, company_id)
				VALUES ('CDG 86 User', ?, ?, ?, ?)
				""", email, passwords.encode("Example123"), role.name(), assignedCompanyId);
		Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		return new AuthenticatedUser(id, email, role, Instant.now().plusSeconds(3600));
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}
}
