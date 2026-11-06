package com.drift.backend.maritimeai;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/** Refuses Flyway startup unless this test owns one new, empty CDG-289 PostgreSQL database. */
final class Cdg289ScenarioAssessmentDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
	private static final Pattern URL = Pattern.compile(
			"^jdbc:postgresql://127\\.0\\.0\\.1:5432/(drift_cdg289_[0-9]{14}_[a-f0-9]{12})$");

	@Override public void initialize(ConfigurableApplicationContext context) {
		ConfigurableEnvironment environment = context.getEnvironment();
		String[] profiles = environment.getActiveProfiles();
		if (profiles.length != 1 || !"test".equals(profiles[0])) throw new IllegalStateException("CDG-289 requires only the test profile.");
		String url = environment.getProperty("spring.datasource.url");
		String expected = environment.getProperty("drift.startup-guard.expected-url");
		String username = environment.getProperty("spring.datasource.username");
		String password = environment.getProperty("spring.datasource.password");
		String flywayUrl = environment.getProperty("spring.flyway.url");
		String flywayUser = environment.getProperty("spring.flyway.user");
		String flywayPassword = environment.getProperty("spring.flyway.password");
		String token = environment.getProperty("drift.startup-guard.ownership-token");
		java.util.regex.Matcher matcher = url == null ? URL.matcher("") : URL.matcher(url);
		if (url == null || !url.equals(expected) || !matcher.matches() || username == null || password == null
				|| !url.equals(flywayUrl) || !username.equals(flywayUser) || !password.equals(flywayPassword)
				|| token == null || token.isBlank()) throw new IllegalStateException("CDG-289 requires one explicit owned disposable PostgreSQL target.");
		require(environment, "spring.flyway.out-of-order", "false");
		require(environment, "app.ais.enabled", "false");
		require(environment, "app.maritime-ai.external-requests-approved", "false");
		require(environment, "app.maritime-ai.provider", "");
		require(environment, "app.maritime-ai.model", "");
		require(environment, "app.maritime-ai.endpoint", "");
		require(environment, "app.maritime-ai.api-key", "");
		try (Connection connection = DriverManager.getConnection(url, username, password)) {
			if (!matcher.group(1).equals(query(connection, "SELECT current_database()"))
					|| !"5432".equals(query(connection, "SELECT inet_server_port()::text"))
					|| !"0".equals(query(connection, "SELECT COUNT(*)::text FROM information_schema.tables WHERE table_schema='public'"))
					|| !"absent".equals(query(connection, "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'absent' ELSE 'present' END"))
					|| !token.equals(query(connection, "SELECT COALESCE(pg_catalog.shobj_description(oid, 'pg_database'), '') FROM pg_catalog.pg_database WHERE datname=current_database()"))) {
				throw new IllegalStateException("CDG-289 refused a database without an exact disposable-database ownership marker.");
			}
		} catch (SQLException exception) { throw new IllegalStateException("CDG-289 could not verify its owned database.", exception); }
	}

	private static void require(ConfigurableEnvironment environment, String key, String expected) {
		if (!expected.equals(environment.getProperty(key))) throw new IllegalStateException("CDG-289 requires " + key + "=" + expected + ".");
	}
	private static String query(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(sql)) {
			if (!results.next()) throw new IllegalStateException("CDG-289 database ownership query returned no row.");
			return results.getString(1);
		}
	}
}
