package com.drift.backend.company;

import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts the authorisation row existing shipment-link tests need after CDG-339. */
public final class TestAuthorisations {

	private TestAuthorisations() {
	}

	public static void allowShipment(JdbcTemplate jdbc, long shipmentId, long importerCompanyId) {
		Long forwarderCompanyId = jdbc.queryForObject("SELECT company_id FROM shipments WHERE id = ?", Long.class, shipmentId);
		Long actorId = jdbc.queryForObject("""
				SELECT id FROM users WHERE company_id = ? ORDER BY id LIMIT 1
				""", Long.class, forwarderCompanyId);
		jdbc.update("""
				INSERT INTO forwarder_importer_relationships (
					forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
				VALUES (?, ?, TRUE, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
				ON CONFLICT (forwarder_company_id, importer_company_id)
				DO UPDATE SET active = TRUE, updated_at = CURRENT_TIMESTAMP, updated_by_user_id = EXCLUDED.updated_by_user_id
				""", forwarderCompanyId, importerCompanyId, actorId, actorId);
	}
}
