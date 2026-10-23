package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A real V24-to-V25 upgrade test. It is deliberately opt-in: setting
 * CDG198_MIGRATION_DATABASE_URL to a database whose name starts with
 * {@code drift_cdg198_} is required before it will mutate anything.
 */
class ShipmentRiskAlertActivationMigrationIntegrationTest {

	@Test
	void upgradesHistoricalRiskRecordsWithoutChangingTheirAuditData() throws Exception {
		String url = System.getenv("CDG198_MIGRATION_DATABASE_URL");
		String user = System.getenv().getOrDefault("CDG198_MIGRATION_DATABASE_USERNAME", "drift");
		String password = System.getenv().getOrDefault("CDG198_MIGRATION_DATABASE_PASSWORD", "drift");
		Assumptions.assumeTrue(url != null && url.matches("jdbc:postgresql://[^/]+/drift_cdg198_[a-z0-9_]+"),
				"Set CDG198_MIGRATION_DATABASE_URL to an approved drift_cdg198_* disposable database");

		Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("24").load().migrate();
		long shipmentId;
		long transitionId;
		long userId;
		try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
			var company = statement.executeQuery("INSERT INTO companies(code,name,active) VALUES ('CDG198_MIG','Migration',true) RETURNING id");
			company.next(); long companyId = company.getLong(1);
			var role = statement.executeQuery("SELECT id FROM roles WHERE code = 'FREIGHT_FORWARDER'");
			role.next(); long roleId = role.getLong(1);
			var account = statement.executeQuery("INSERT INTO users(full_name,email,password_hash,role_id,company_id,created_at,session_generation,risk_email_notifications_enabled,minimum_risk_notification_level,status) VALUES ('Migration','cdg198-migration@example.test','hash'," + roleId + "," + companyId + ",CURRENT_TIMESTAMP,0,true,'HIGH','ACTIVE') RETURNING id");
			account.next(); userId = account.getLong(1);
			var shipment = statement.executeQuery("INSERT INTO shipments(company_id,created_by_user_id,updated_by_user_id,shipment_reference,origin,destination,transshipment_port,mother_vessel,planned_mother_arrival_at,feeder_vessel,planned_feeder_departure_at,status,created_at,updated_at) VALUES (" + companyId + "," + userId + "," + userId + ",'CDG198-MIG','A','B','C','Mother',CURRENT_TIMESTAMP,'Feeder',CURRENT_TIMESTAMP + interval '1 day','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id");
			shipment.next(); shipmentId = shipment.getLong(1);
			statement.executeUpdate("INSERT INTO shipment_risk_states(shipment_id,baseline_mode,last_transition_sequence,created_at,updated_at) VALUES (" + shipmentId + ",'SILENT_FIRST_VALID',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
			var transition = statement.executeQuery("INSERT INTO shipment_risk_transitions(shipment_id,sequence,transition_type,risk_level,explanation,occurred_at,created_at,recipient_resolution_completed_at) VALUES (" + shipmentId + ",1,'INITIAL','HIGH','historical',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id");
			transition.next(); transitionId = transition.getLong(1);
			statement.executeUpdate("INSERT INTO shipment_risk_alert_deliveries(transition_id,user_id,status,attempt_count,next_attempt_at,created_at,updated_at) VALUES (" + transitionId + "," + userId + ",'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
		}
		Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
		try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
			var activation = statement.executeQuery("SELECT generation, activated_at FROM shipment_risk_alert_activation WHERE id=1"); activation.next();
			assertThat(activation.getLong(1)).isZero(); assertThat(activation.getObject(2)).isNull();
			var state = statement.executeQuery("SELECT activation_generation FROM shipment_risk_states WHERE shipment_id=" + shipmentId); state.next();
			assertThat(state.getLong(1)).isZero();
			var transition = statement.executeQuery("SELECT activation_generation FROM shipment_risk_transitions WHERE id=" + transitionId); transition.next();
			assertThat(transition.getLong(1)).isZero();
			var deliveries = statement.executeQuery("SELECT count(*) FROM shipment_risk_alert_deliveries WHERE transition_id=" + transitionId); deliveries.next();
			assertThat(deliveries.getLong(1)).isEqualTo(1L);
		}
	}
}
