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

/** Refuses Spring/Flyway startup unless the test owns one new, empty CDG-279 database. */
final class SyntheticDisruptionDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
	static final Pattern URL = Pattern.compile(
			"^jdbc:postgresql://127\\.0\\.0\\.1:5432/(drift_cdg279_[0-9]{14}_[a-f0-9]{12})$");

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		ConfigurableEnvironment environment = context.getEnvironment();
		String[] profiles = environment.getActiveProfiles();
		if (profiles.length != 1 || !"test".equals(profiles[0])) {
			throw new IllegalStateException("CDG-279 verification requires only the test profile.");
		}
		String expectedUrl = environment.getProperty("drift.startup-guard.expected-url");
		String ownershipToken = environment.getProperty("drift.startup-guard.ownership-token");
		String url = environment.getProperty("spring.datasource.url");
		String username = environment.getProperty("spring.datasource.username");
		String password = environment.getProperty("spring.datasource.password");
		String flywayUrl = environment.getProperty("spring.flyway.url");
		String flywayUser = environment.getProperty("spring.flyway.user");
		String flywayPassword = environment.getProperty("spring.flyway.password");
		if (expectedUrl == null || ownershipToken == null || url == null || username == null || password == null
				|| flywayUrl == null || flywayUser == null || flywayPassword == null) {
			throw new IllegalStateException("CDG-279 verification requires an explicit owned database target.");
		}
		Matcher matcher = URL.matcher(url);
		if (!url.equals(expectedUrl) || !flywayUrl.equals(expectedUrl) || !matcher.matches()
				|| url.endsWith("/drift") || url.endsWith("/drift_test")) {
			throw new IllegalStateException("CDG-279 verification refused its database URL.");
		}
		if (!username.equals(flywayUser) || !password.equals(flywayPassword)) {
			throw new IllegalStateException("CDG-279 verification refused mismatched database credentials.");
		}
		requireFalse(environment, "app.ais.enabled");
		requireFalse(environment, "app.risk-alerts.activation.command");
		requireFalse(environment, "app.risk-alerts.evaluation.enabled");
		requireFalse(environment, "app.risk-alerts.mail.enabled");
		requireFalse(environment, "app.risk-alerts.mail.dispatch-enabled");
		requireFalse(environment, "app.maritime-context.demo-enabled");
		requireFalse(environment, "app.maritime-ai.controlled-replay-stub-enabled");
		requireFalse(environment, "app.maritime-ai.external-requests-approved");
		requireFalse(environment, "app.maritime-ai.authentic-retrospective-inference-approved");
		requireBlank(environment, "app.maritime-ai.provider");
		requireBlank(environment, "app.maritime-ai.model");
		requireBlank(environment, "app.maritime-ai.endpoint");
		requireBlank(environment, "app.maritime-ai.api-key");
		if (!"true".equals(environment.getProperty("app.maritime-ai.enabled"))
				|| !"false".equals(environment.getProperty("spring.flyway.out-of-order"))
				|| !"".equals(environment.getProperty("app.maritime-ai.authentic-retrospective-source-types-approved"))
				|| !"".equals(environment.getProperty("app.maritime-ai.authentic-retrospective-evidence-ids-approved"))) {
			throw new IllegalStateException("CDG-279 verification requires offline AI, deny-by-default external processing, and ordered migrations.");
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
					FROM pg_catalog.pg_database d WHERE d.datname = current_database()
					""");
			if (!database.equals(currentDatabase) || !"5432".equals(port) || !"0".equals(tableCount)
					|| !"absent".equals(history) || !ownershipToken.equals(comment)) {
				throw new IllegalStateException("CDG-279 verification refused a database that is not new and explicitly owned.");
			}
			System.out.println("SyntheticDisruptionDatabaseGuard phase=before-context-refresh database=" + currentDatabase
					+ " publicTableCount=0 flywaySchemaHistoryPresent=false app.ais.enabled=false externalAiApproved=false");
		} catch (SQLException exception) {
			throw new IllegalStateException("CDG-279 verification could not inspect its database.", exception);
		}
	}

	private static void requireFalse(ConfigurableEnvironment environment, String property) {
		if (!"false".equals(environment.getProperty(property))) {
			throw new IllegalStateException("CDG-279 verification requires " + property + "=false.");
		}
	}

	private static void requireBlank(ConfigurableEnvironment environment, String property) {
		if (!"".equals(environment.getProperty(property))) {
			throw new IllegalStateException("CDG-279 verification requires " + property + " to be blank.");
		}
	}

	private static String query(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(sql)) {
			if (!results.next()) throw new IllegalStateException("CDG-279 verification query returned no row.");
			return results.getString(1);
		}
	}
}
