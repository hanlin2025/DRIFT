package com.drift.backend.maritimechange;

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

/** Refuses to run the endpoint integration test against any database except a new owned disposable database. */
final class MaritimeChangeEventDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
	private static final Pattern URL = Pattern.compile(
			"^jdbc:postgresql://127\\.0\\.0\\.1:5432/(drift_cdg358_[0-9]{14}_[a-f0-9]{12})$");

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		ConfigurableEnvironment env = context.getEnvironment();
		String[] profiles = env.getActiveProfiles();
		if (profiles.length != 1 || !"test".equals(profiles[0])) throw new IllegalStateException("CDG-358 endpoint test requires only the test profile.");
		String url = env.getProperty("spring.datasource.url");
		String flywayUrl = env.getProperty("spring.flyway.url");
		String username = env.getProperty("spring.datasource.username");
		String password = env.getProperty("spring.datasource.password");
		String flywayUser = env.getProperty("spring.flyway.user");
		String flywayPassword = env.getProperty("spring.flyway.password");
		String expected = env.getProperty("drift.startup-guard.expected-url");
		String owner = env.getProperty("drift.startup-guard.ownership-token");
		if (url == null || flywayUrl == null || username == null || password == null || flywayUser == null
				|| flywayPassword == null || expected == null || owner == null || owner.isBlank()) {
			throw new IllegalStateException("CDG-358 endpoint test requires explicit disposable database configuration.");
		}
		Matcher matcher = URL.matcher(url);
		if (!matcher.matches() || !url.equals(expected) || !url.equals(flywayUrl)
				|| !username.equals(flywayUser) || !password.equals(flywayPassword)
				|| url.endsWith("/drift") || url.endsWith("/drift_test")) {
			throw new IllegalStateException("CDG-358 endpoint test refused database URL " + url);
		}
		try (Connection connection = DriverManager.getConnection(url, username, password)) {
			String database = query(connection, "SELECT current_database()");
			String port = query(connection, "SELECT inet_server_port()::text");
			String tables = query(connection, "SELECT COUNT(*)::text FROM information_schema.tables WHERE table_schema = 'public'");
			String history = query(connection, "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'absent' ELSE 'present' END");
			String comment = query(connection, """
					SELECT COALESCE(pg_catalog.shobj_description(d.oid, 'pg_database'), '')
					FROM pg_catalog.pg_database d WHERE d.datname = current_database()
					""");
			if (!database.equals(matcher.group(1)) || !"5432".equals(port) || !"0".equals(tables)
					|| !"absent".equals(history) || !owner.equals(comment)) {
				throw new IllegalStateException("CDG-358 endpoint test refused a non-empty or unowned database before migration.");
			}
		} catch (IllegalStateException exception) {
			throw exception;
		} catch (SQLException exception) {
			throw new IllegalStateException("CDG-358 endpoint test could not verify its database target.", exception);
		}
	}

	private static String query(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
			if (!result.next()) throw new IllegalStateException("Database guard query returned no row.");
			return result.getString(1);
		}
	}
}
