package com.drift.backend.shipment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Rejects the arrival-coverage context before refresh unless both the datasource and Flyway
 * target one new empty disposable database.
 */
final class ArrivalCoverageDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	static final Pattern URL = Pattern.compile(
			"^jdbc:postgresql://127\\.0\\.0\\.1:5432/(drift_cdg152_coverage_[0-9]{14}_[a-f0-9]{12})$");

	@Override
	public void initialize(ConfigurableApplicationContext applicationContext) {
		ConfigurableEnvironment environment = applicationContext.getEnvironment();
		String[] profiles = environment.getActiveProfiles();
		if (profiles.length != 1 || !"test".equals(profiles[0])) {
			throw new IllegalStateException("Arrival coverage requires only the test profile.");
		}
		String expectedUrl = environment.getProperty("drift.startup-guard.expected-url");
		String ownershipToken = environment.getProperty("drift.startup-guard.ownership-token");
		String url = environment.getProperty("spring.datasource.url");
		String username = environment.getProperty("spring.datasource.username");
		String password = environment.getProperty("spring.datasource.password");
		String flywayUrl = environment.getProperty("spring.flyway.url");
		String flywayUser = environment.getProperty("spring.flyway.user");
		String flywayPassword = environment.getProperty("spring.flyway.password");
		String aisEnabled = environment.getProperty("app.ais.enabled");
		if (expectedUrl == null || ownershipToken == null || url == null || username == null || password == null
				|| flywayUrl == null || flywayUser == null || flywayPassword == null) {
			throw new IllegalStateException("Arrival coverage is missing an explicit database target.");
		}
		Matcher matcher = URL.matcher(url);
		if (!url.equals(expectedUrl) || !flywayUrl.equals(expectedUrl) || !matcher.matches()
				|| url.endsWith("/drift") || url.endsWith("/drift_test")) {
			throw new IllegalStateException("Arrival coverage refused database URL " + url);
		}
		if (!username.equals(flywayUser) || !password.equals(flywayPassword)) {
			throw new IllegalStateException("Arrival coverage refused mismatched database credentials.");
		}
		if (!"false".equals(aisEnabled)) {
			throw new IllegalStateException("Arrival coverage requires app.ais.enabled=false.");
		}
		String database = matcher.group(1);
		try (Connection connection = DriverManager.getConnection(url, username, password)) {
			String currentDatabase = query(connection, "SELECT current_database()");
			String port = query(connection, "SELECT inet_server_port()::text");
			String tableCount = query(connection,
					"SELECT COUNT(*)::text FROM information_schema.tables WHERE table_schema = 'public'");
			String history = query(connection,
					"SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'absent' ELSE 'present' END");
			String comment = query(connection, """
					SELECT COALESCE(pg_catalog.shobj_description(d.oid, 'pg_database'), '')
					FROM pg_catalog.pg_database d
					WHERE d.datname = current_database()
					""");
			if (!database.equals(currentDatabase) || !"5432".equals(port) || !"0".equals(tableCount)
					|| !"absent".equals(history) || !ownershipToken.equals(comment)) {
				throw new IllegalStateException("Arrival coverage refused database " + currentDatabase
						+ " before migration (tables=" + tableCount + ", flyway=" + history + ", port=" + port + ").");
			}
			System.out.println("ArrivalCoverageDatabaseGuard phase=before-context-refresh database=" + currentDatabase
					+ " publicTableCount=" + tableCount + " flywaySchemaHistoryPresent=false app.ais.enabled="
					+ aisEnabled + " datasource=" + url);
		} catch (IllegalStateException exception) {
			throw exception;
		} catch (SQLException exception) {
			throw new IllegalStateException("Arrival coverage could not inspect its database.", exception);
		}
	}

	private static String query(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(sql)) {
			if (!results.next()) {
				throw new IllegalStateException("Arrival coverage query returned no row.");
			}
			return results.getString(1);
		}
	}
}
