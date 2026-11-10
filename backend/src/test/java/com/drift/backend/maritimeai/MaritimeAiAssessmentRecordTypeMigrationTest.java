package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

/** Exercises V33-to-V34 classification only on uniquely named, locally owned disposable databases. */
class MaritimeAiAssessmentRecordTypeMigrationTest {
	private static final String ORDINARY = MaritimeAiStoredAssessmentContent.SHIPMENT_ASSESSMENT;
	private static final String SCENARIO = MaritimeAiStoredAssessmentContent.CONTROLLED_SCENARIO_ASSESSMENT;

	@Test
	void classifiesExistingScenarioRowsAndPreservesLegacyOrdinaryRows() throws Exception {
		Database database = createDisposableDatabase();
		try {
			migrateTo33(database.url);
			try (Connection connection = DriverManager.getConnection(database.url, database.username, database.password)) {
				long shipmentId = seedShipment(connection);
				insertAssessment(connection, shipmentId, "ordinary-absent", "{\"interpretation\":{},\"deterministicSummary\":{}}");
				insertAssessment(connection, shipmentId, "ordinary-null", "{\"recordType\":null,\"scenarioContext\":null,\"interpretation\":{}}");
				insertAssessment(connection, shipmentId, "scenario-valid",
						"{\"recordType\":\"CONTROLLED_SCENARIO_ASSESSMENT\",\"scenarioContext\":{\"sessionId\":\"legacy-session\"}}");
				insertAssessment(connection, shipmentId, "scenario-context-only",
						"{\"scenarioContext\":{\"sessionId\":\"legacy-session\"},\"interpretation\":{}}");
			}

			Flyway.configure().dataSource(database.url, database.username, database.password)
				.locations("classpath:db/migration").outOfOrder(false).load().migrate();
			try (Connection connection = DriverManager.getConnection(database.url, database.username, database.password)) {
				assertThat(recordType(connection, "ordinary-absent")).isEqualTo(ORDINARY);
				assertThat(recordType(connection, "ordinary-null")).isEqualTo(ORDINARY);
				assertThat(recordType(connection, "scenario-valid")).isEqualTo(SCENARIO);
				assertThat(recordType(connection, "scenario-context-only")).isEqualTo(SCENARIO);
				assertThat(migrationVersions(connection)).endsWith("32", "33", "34", "35", "36", "37", "38", "40");
			}
		} finally {
			dropOwnedDatabase(database);
		}
	}

	@Test
	void refusesToGuessTheTypeOfMalformedExistingAssessmentJson() throws Exception {
		Database database = createDisposableDatabase();
		try {
			migrateTo33(database.url);
			try (Connection connection = DriverManager.getConnection(database.url, database.username, database.password)) {
				long shipmentId = seedShipment(connection);
				insertAssessment(connection, shipmentId, "malformed", "{\"recordType\":");
			}

			Flyway flyway = Flyway.configure().dataSource(database.url, database.username, database.password)
				.locations("classpath:db/migration").outOfOrder(false).load();
			assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
			try (Connection connection = DriverManager.getConnection(database.url, database.username, database.password)) {
				assertThat(columnExists(connection, "maritime_ai_assessments", "record_type")).isFalse();
				assertThat(migrationVersions(connection)).endsWith("32", "33");
			}
		} finally {
			dropOwnedDatabase(database);
		}
	}

	private static Database createDisposableDatabase() throws Exception {
		String adminUrl = required("DRIFT_CDG289_ADMIN_JDBC_URL");
		String username = required("DRIFT_CDG289_DB_USERNAME");
		String password = required("DRIFT_CDG289_DB_PASSWORD");
		if (!adminUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:5432/postgres")) {
			throw new IllegalStateException("V34 migration tests require the local disposable PostgreSQL admin database.");
		}
		String name = "drift_cdg289_v34_" + System.currentTimeMillis() + "_"
				+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String token = UUID.randomUUID().toString().replace("-", "");
		if (!name.matches("drift_cdg289_v34_[0-9]+_[a-f0-9]{12}")) throw new IllegalStateException("Invalid disposable database name.");
		try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
				Statement statement = connection.createStatement()) {
				if (exists(connection, name)) throw new IllegalStateException("Refusing to reuse an existing V34 database.");
				statement.execute("CREATE DATABASE " + name);
				statement.execute("COMMENT ON DATABASE " + name + " IS '" + token + "'");
		}
		return new Database(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + name, adminUrl, username, password, name, token);
	}

	private static void migrateTo33(String url) {
		Flyway.configure().dataSource(url, required("DRIFT_CDG289_DB_USERNAME"), required("DRIFT_CDG289_DB_PASSWORD"))
			.locations("classpath:db/migration").target("33").outOfOrder(false).load().migrate();
	}

	private static long seedShipment(Connection connection) throws Exception {
		long companyId = scalar(connection, "SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'");
		long roleId = scalar(connection, "SELECT id FROM roles WHERE code = 'FREIGHT_FORWARDER'");
		long userId;
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				VALUES ('V34 migration test', ?, 'hash', ?, ?) RETURNING id
				""")) {
			statement.setString(1, "v34-" + UUID.randomUUID() + "@example.com");
			statement.setLong(2, roleId);
			statement.setLong(3, companyId);
			try (ResultSet rows = statement.executeQuery()) { rows.next(); userId = rows.getLong(1); }
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO shipments (
					company_id, created_by_user_id, updated_by_user_id, shipment_reference, origin, destination,
					transshipment_port, mother_vessel, planned_mother_arrival_at, feeder_vessel,
					planned_feeder_departure_at, updated_at)
				VALUES (?, ?, ?, ?, 'Jakarta', 'Rotterdam', 'Singapore', 'Mother', ?, 'Feeder', ?, ?)
				RETURNING id
				""")) {
			statement.setLong(1, companyId);
			statement.setLong(2, userId);
			statement.setLong(3, userId);
			statement.setString(4, "V34-" + UUID.randomUUID());
			statement.setObject(5, OffsetDateTime.ofInstant(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC));
			statement.setObject(6, OffsetDateTime.ofInstant(Instant.parse("2026-11-11T08:00:00Z"), ZoneOffset.UTC));
			statement.setObject(7, OffsetDateTime.ofInstant(Instant.parse("2026-11-01T08:00:00Z"), ZoneOffset.UTC));
			try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
		}
	}

	private static void insertAssessment(Connection connection, long shipmentId, String id, String output) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO maritime_ai_assessments (
					shipment_id, assessment_id, owning_company_id, bundle_version, bundle_hash, assessment_cutoff_utc,
					provider, model, prompt_version, generation_state, validation_status, output_json, created_at)
				SELECT ?, ?, company_id, 'legacy-v1', repeat('a', 64), '2026-11-01T00:00:00Z',
					'legacy', 'legacy', 'legacy-v1', 'GENERATED', 'VALID', ?, NOW()
				FROM shipments WHERE id = ?
				""")) {
			statement.setLong(1, shipmentId);
			statement.setString(2, id);
			statement.setString(3, output);
			statement.setLong(4, shipmentId);
			assertThat(statement.executeUpdate()).isEqualTo(1);
		}
	}

	private static String recordType(Connection connection, String id) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("SELECT record_type FROM maritime_ai_assessments WHERE assessment_id = ?")) {
			statement.setString(1, id);
			try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
		}
	}

	private static java.util.List<String> migrationVersions(Connection connection) throws Exception {
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
			java.util.List<String> result = new java.util.ArrayList<>();
			while (rows.next()) if (rows.getString(1) != null) result.add(rows.getString(1));
			return result;
		}
	}

	private static boolean columnExists(Connection connection, String table, String column) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT EXISTS (SELECT 1 FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = ? AND column_name = ?)
				""")) {
			statement.setString(1, table);
			statement.setString(2, column);
			try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getBoolean(1); }
		}
	}

	private static long scalar(Connection connection, String sql) throws Exception {
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) { rows.next(); return rows.getLong(1); }
	}

	private static boolean exists(Connection connection, String database) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = ?)")) {
			statement.setString(1, database);
			try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getBoolean(1); }
		}
	}

	private static void dropOwnedDatabase(Database database) throws Exception {
		try (Connection connection = DriverManager.getConnection(database.adminUrl, database.username, database.password)) {
			if (!exists(connection, database.name)) return;
			String marker;
			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT COALESCE(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = ?")) {
				statement.setString(1, database.name);
				try (ResultSet rows = statement.executeQuery()) { rows.next(); marker = rows.getString(1); }
			}
			if (!database.token.equals(marker)) throw new IllegalStateException("Refusing to drop a V34 database with a changed ownership marker.");
			try (Statement statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database.name); }
		}
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for disposable V34 migration tests.");
		return value;
	}

	private record Database(String url, String adminUrl, String username, String password, String name, String token) { }
}
