package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Migrates a disposable database through V30, stores representative current decisions,
 * then applies V32. V31 is left unused because that version belongs to another pending migration.
 */
class RecommendationDecisionHistoryBackfillTest {

	@Test
	void copiesExistingDecisionsWhenHistoryIsAppliedAfterThePrecedingMigrations() throws Exception {
		String adminUrl = required("DRIFT_CDG53_ADMIN_JDBC_URL");
		String username = required("DRIFT_CDG53_DB_USERNAME");
		String password = required("DRIFT_CDG53_DB_PASSWORD");
		refuseSharedDatabase(adminUrl);
		String databaseName = "drift_cdg53_backfill_" + System.currentTimeMillis() + "_"
				+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		createDatabase(adminUrl, username, password, databaseName);
		String url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + databaseName;

		Flyway.configure()
				.dataSource(url, username, password)
				.locations("classpath:db/migration")
				.target("30")
				.outOfOrder(false)
				.load()
				.migrate();

		try (Connection connection = DriverManager.getConnection(url, username, password)) {
			assertThat(tableExists(connection, "recommendation_decisions")).isTrue();
			assertThat(tableExists(connection, "recommendation_decision_history")).isFalse();
			Seed seed = seed(connection);
			List<DecisionRow> before = currentRows(connection);

			Flyway.configure()
					.dataSource(url, username, password)
					.locations("classpath:db/migration")
					.outOfOrder(false)
					.load()
					.migrate();

			assertThat(currentRows(connection)).isEqualTo(before);
			assertThat(historyCount(connection, seed.emptyShipmentId)).isZero();
			assertThat(historyRows(connection)).containsExactlyInAnyOrderElementsOf(expectedHistory(before, seed));
			assertThat(actorName(connection, seed.adaId)).isEqualTo("Ada Lee");
			try (PreparedStatement rename = connection.prepareStatement("UPDATE users SET full_name = ? WHERE id = ?")) {
				rename.setString(1, "Ada Renamed");
				rename.setLong(2, seed.adaId);
				assertThat(rename.executeUpdate()).isEqualTo(1);
			}
			assertThat(historyRows(connection).stream()
					.filter(row -> row.actorId == seed.adaId)
					.map(row -> row.actorName))
					.containsOnly("Ada Lee");
			assertThat(versions(connection)).doesNotContain("31").endsWith("30", "32");
			assertThat(monotonic(versions(connection))).isTrue();
		}
	}

	private static Seed seed(Connection connection) throws Exception {
		long companyId = queryLong(connection, "SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'");
		long roleId = queryLong(connection, "SELECT id FROM roles WHERE code = 'FREIGHT_FORWARDER'");
		long adaId = insertUser(connection, companyId, roleId, "Ada Lee", "ada-" + UUID.randomUUID() + "@example.com");
		long boId = insertUser(connection, companyId, roleId, "Bo Chen", "bo-" + UUID.randomUUID() + "@example.com");
		long approvedShipment = insertShipment(connection, companyId, adaId, "BF-APPROVED-");
		long modifiedShipment = insertShipment(connection, companyId, adaId, "BF-MODIFIED-");
		long dismissedShipment = insertShipment(connection, companyId, boId, "BF-DISMISSED-");
		long emptyShipment = insertShipment(connection, companyId, adaId, "BF-NONE-");
		insertDecision(connection, approvedShipment, "APPROVED", "Hold connection", "Keep the planned connection.",
				null, null, adaId, Instant.parse("2026-10-01T01:00:00Z"));
		insertDecision(connection, modifiedShipment, "MODIFIED", "Hold connection", "Keep the planned connection.",
				"Advance feeder", "Move the feeder departure earlier.", boId, Instant.parse("2026-10-02T02:00:00Z"));
		insertDecision(connection, dismissedShipment, "DISMISSED", "No action", "No action is needed.",
				null, null, adaId, Instant.parse("2026-10-03T03:00:00Z"));
		return new Seed(adaId, boId, emptyShipment);
	}

	private static long insertUser(Connection connection, long companyId, long roleId, String name, String email)
			throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				VALUES (?, ?, 'hash', ?, ?)
				RETURNING id
				""")) {
			statement.setString(1, name);
			statement.setString(2, email);
			statement.setLong(3, roleId);
			statement.setLong(4, companyId);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private static long insertShipment(Connection connection, long companyId, long userId, String referencePrefix)
			throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO shipments (
					company_id, created_by_user_id, updated_by_user_id, shipment_reference,
					origin, destination, transshipment_port, mother_vessel, planned_mother_arrival_at,
					feeder_vessel, planned_feeder_departure_at, updated_at)
				VALUES (?, ?, ?, ?, 'Jakarta', 'Rotterdam', 'Singapore', 'Mother', ?, 'Feeder', ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, companyId);
			statement.setLong(2, userId);
			statement.setLong(3, userId);
			statement.setString(4, referencePrefix + UUID.randomUUID());
			statement.setObject(5, OffsetDateTime.ofInstant(Instant.parse("2026-10-15T08:00:00Z"), ZoneOffset.UTC));
			statement.setObject(6, OffsetDateTime.ofInstant(Instant.parse("2026-10-16T08:00:00Z"), ZoneOffset.UTC));
			statement.setObject(7, OffsetDateTime.ofInstant(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC));
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getLong(1);
			}
		}
	}

	private static void insertDecision(Connection connection, long shipmentId, String outcome, String name, String wording,
			String selectedName, String selectedWording, long actorId, Instant decidedAt) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO recommendation_decisions (
					shipment_id, outcome, recommendation_name, recommendation_wording,
					selected_response_name, selected_response_wording, decided_by_user_id, decided_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			statement.setLong(1, shipmentId);
			statement.setString(2, outcome);
			statement.setString(3, name);
			statement.setString(4, wording);
			statement.setString(5, selectedName);
			statement.setString(6, selectedWording);
			statement.setLong(7, actorId);
			statement.setObject(8, decidedAt.atOffset(ZoneOffset.UTC));
			statement.executeUpdate();
		}
	}

	private static List<DecisionRow> currentRows(Connection connection) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT shipment_id, outcome, recommendation_name, recommendation_wording,
					selected_response_name, selected_response_wording, decided_by_user_id, decided_at
				FROM recommendation_decisions
				ORDER BY shipment_id
				""");
				ResultSet rows = statement.executeQuery()) {
			List<DecisionRow> result = new ArrayList<>();
			while (rows.next()) {
				result.add(new DecisionRow(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4),
						rows.getString(5), rows.getString(6), rows.getLong(7), null,
						rows.getObject(8, OffsetDateTime.class).toInstant()));
			}
			return result;
		}
	}

	private static List<DecisionRow> historyRows(Connection connection) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT shipment_id, outcome, recommendation_name, recommendation_wording,
					selected_response_name, selected_response_wording, decided_by_user_id, decided_by_name, decided_at
				FROM recommendation_decision_history
				ORDER BY shipment_id
				""");
				ResultSet rows = statement.executeQuery()) {
			List<DecisionRow> result = new ArrayList<>();
			while (rows.next()) {
				result.add(new DecisionRow(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4),
						rows.getString(5), rows.getString(6), rows.getLong(7), rows.getString(8),
						rows.getObject(9, OffsetDateTime.class).toInstant()));
			}
			return result;
		}
	}

	private static List<DecisionRow> expectedHistory(List<DecisionRow> current, Seed seed) {
		return current.stream()
				.map(row -> new DecisionRow(row.shipmentId, row.outcome, row.recommendationName, row.recommendationWording,
						row.selectedName, row.selectedWording, row.actorId,
						row.actorId == seed.adaId ? "Ada Lee" : "Bo Chen", row.decidedAt))
				.toList();
	}

	private static int historyCount(Connection connection, long shipmentId) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT COUNT(*) FROM recommendation_decision_history WHERE shipment_id = ?")) {
			statement.setLong(1, shipmentId);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getInt(1);
			}
		}
	}

	private static String actorName(Connection connection, long userId) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("SELECT full_name FROM users WHERE id = ?")) {
			statement.setLong(1, userId);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getString(1);
			}
		}
	}

	private static List<String> versions(Connection connection) throws Exception {
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(
						"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
			List<String> versions = new ArrayList<>();
			while (rows.next()) {
				versions.add(rows.getString(1));
			}
			return versions;
		}
	}

	private static boolean monotonic(List<String> versions) {
		int previous = 0;
		for (String version : versions) {
			int current = Integer.parseInt(version);
			if (current < previous) {
				return false;
			}
			previous = current;
		}
		return true;
	}

	private static boolean tableExists(Connection connection, String table) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT to_regclass(?) IS NOT NULL")) {
			statement.setString(1, "public." + table);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getBoolean(1);
			}
		}
	}

	private static long queryLong(Connection connection, String sql) throws Exception {
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			rows.next();
			return rows.getLong(1);
		}
	}

	private static void createDatabase(String adminUrl, String username, String password, String databaseName)
			throws Exception {
		try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + databaseName);
		}
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required and must point at the disposable CDG-53 Postgres");
		}
		return value;
	}

	private static void refuseSharedDatabase(String url) {
		if (url.contains(":5432/") || url.matches("(?i).*/(drift|drift_test)(\\?.*)?$")) {
			throw new IllegalStateException("Refusing shared database " + url);
		}
	}

	private record Seed(long adaId, long boId, long emptyShipmentId) {
	}

	private record DecisionRow(long shipmentId, String outcome, String recommendationName, String recommendationWording,
			String selectedName, String selectedWording, long actorId, String actorName, Instant decidedAt) {
	}
}
