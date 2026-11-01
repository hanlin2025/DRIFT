package com.drift.backend.shipment.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.shipment.Shipment;
import com.drift.backend.shipment.ShipmentRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentChangeHistorySchemaIntegrationTests {

	@Autowired UserAccountRepository accounts;
	@Autowired AccountRoleRepository roles;
	@Autowired CompanyRepository companies;
	@Autowired ShipmentRepository shipments;
	@Autowired ShipmentChangeEventRepository events;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManager entityManager;

	@Test
	void storesAnEventWithItsFieldChanges() {
		UserAccount editor = accounts.save(account());
		Shipment shipment = shipments.saveAndFlush(shipment(editor));
		Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		ShipmentChangeEvent event = new ShipmentChangeEvent(shipment, editor, ShipmentChangeType.UPDATED, recordedAt);
		event.addFieldChange("feederVessel", "Straits Feeder", "Kota Feeder");
		event.addFieldChange("motherVesselImo", null, "9811000");
		event = events.saveAndFlush(event);
		entityManager.clear();

		ShipmentChangeEvent stored = events.findById(event.getId()).orElseThrow();
		assertThat(stored.getShipment().getId()).isEqualTo(shipment.getId());
		assertThat(stored.getActor().getId()).isEqualTo(editor.getId());
		assertThat(stored.getChangeType()).isEqualTo(ShipmentChangeType.UPDATED);
		assertThat(stored.isEarlierChangesUnrecorded()).isFalse();
		assertThat(stored.getRecordedAt()).isEqualTo(recordedAt);
		assertThat(stored.getFieldChanges())
				.extracting(ShipmentFieldChange::getFieldName, ShipmentFieldChange::getPreviousValue,
						ShipmentFieldChange::getNewValue)
				.containsExactly(
						tuple("feederVessel", "Straits Feeder", "Kota Feeder"),
						tuple("motherVesselImo", null, "9811000"));
	}

	@Test
	void rejectsUnknownChangeTypes() {
		UserAccount editor = accounts.save(account());
		Shipment shipment = shipments.saveAndFlush(shipment(editor));

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO shipment_change_events (shipment_id, actor_user_id, change_type, recorded_at)
				VALUES (?, ?, 'DELETED', NOW())
				""", shipment.getId(), editor.getId()))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void deletingAShipmentRemovesItsHistory() {
		UserAccount editor = accounts.save(account());
		Shipment shipment = shipments.saveAndFlush(shipment(editor));
		ShipmentChangeEvent event = new ShipmentChangeEvent(shipment, editor, ShipmentChangeType.UPDATED, Instant.now());
		event.addFieldChange("origin", "Singapore", "Port Klang");
		events.saveAndFlush(event);

		jdbc.update("DELETE FROM shipments WHERE id = ?", shipment.getId());

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_change_events WHERE shipment_id = ?",
				Long.class, shipment.getId())).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_field_changes WHERE event_id = ?",
				Long.class, event.getId())).isZero();
	}

	private Company harbourline() {
		return companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
	}

	private UserAccount account() {
		return new UserAccount("Alice Tan", "cdg180-" + UUID.randomUUID() + "@example.com", "hash",
				roles.findByCode(Role.FREIGHT_FORWARDER).orElseThrow(), harbourline());
	}

	private Shipment shipment(UserAccount creator) {
		return new Shipment(harbourline(), creator, "HBL-CDG180-" + UUID.randomUUID(), "Singapore", "Jakarta",
				"Singapore", "Ever Steady", OffsetDateTime.parse("2026-10-02T00:00:00Z"), "Straits Feeder",
				OffsetDateTime.parse("2026-10-03T00:00:00Z"));
	}
}
