package com.drift.backend.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import com.drift.backend.BackendApplication;

/**
 * Test-only entry point. Spring Boot prepares the environment and calls
 * ApplicationContextInitializers inside prepareContext, before refreshContext.
 * Flyway migrates during refresh, so a failure here happens before any migration.
 */
public final class GuardedTestApplication {

	private static final Pattern JDBC_URL = Pattern.compile(
			"^jdbc:postgresql://127\\.0\\.0\\.1:5432/(drift_cdg83_[0-9]{14}_[a-f0-9]{12})$");

	private GuardedTestApplication() {
	}

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(BackendApplication.class);
		application.addInitializers(GuardedTestApplication::validateBeforeRefresh);
		application.run(args);
	}

	static void validateBeforeRefresh(ConfigurableApplicationContext context) {
		ConfigurableEnvironment environment = context.getEnvironment();
		String expectedUrl = required(environment, "drift.startup-guard.expected-url");
		String ownershipToken = required(environment, "drift.startup-guard.ownership-token");
		String report = required(environment, "drift.startup-guard.report");
		String datasourceUrl = required(environment, "spring.datasource.url");
		String datasourceUsername = required(environment, "spring.datasource.username");
		String datasourcePassword = required(environment, "spring.datasource.password");
		String flywayUrl = required(environment, "spring.flyway.url");
		String flywayUser = required(environment, "spring.flyway.user");
		String flywayPassword = required(environment, "spring.flyway.password");
		String ais = required(environment, "app.ais.enabled");
		String mailHost = required(environment, "spring.mail.host");
		String mailPort = required(environment, "spring.mail.port");
		String jwtSecret = required(environment, "app.jwt.secret");

		String database = databaseName(expectedUrl);
		if (!ownershipToken.matches("[a-f0-9]{64}")) {
			throw new IllegalStateException("Ownership token is missing or unexpected");
		}
		Path reportPath = reportPath(report, ownershipToken);
		if (!expectedUrl.equals(datasourceUrl) || !expectedUrl.equals(flywayUrl)) {
			throw new IllegalStateException("Datasource URL and Flyway URL must both equal the expected disposable target");
		}
		if (!datasourceUsername.equals(flywayUser) || !datasourcePassword.equals(flywayPassword)) {
			throw new IllegalStateException("Datasource and Flyway credentials disagree");
		}
		String hikariUrl = environment.getProperty("spring.datasource.hikari.jdbc-url");
		if (hikariUrl != null && !hikariUrl.equals(expectedUrl)) {
			throw new IllegalStateException("Hikari JDBC URL conflicts with the expected disposable target");
		}
		String flywayUsername = environment.getProperty("spring.flyway.username");
		if (flywayUsername != null && !flywayUsername.equals(flywayUser)) {
			throw new IllegalStateException("spring.flyway.username conflicts with spring.flyway.user");
		}
		String profiles = environment.getProperty("spring.profiles.active");
		if (profiles != null && !profiles.isBlank()) {
			throw new IllegalStateException("A Spring profile is active; the test profile defaults to drift_test");
		}
		if (!"false".equals(ais)) {
			throw new IllegalStateException("app.ais.enabled must be false so AisStreamConnectionService is not created");
		}
		if (!"127.0.0.1".equals(mailHost) || !"1025".equals(mailPort)) {
			throw new IllegalStateException("Mail must stay on 127.0.0.1:1025");
		}
		if (jwtSecret.isBlank()) {
			throw new IllegalStateException("JWT secret is missing");
		}

		String currentDatabase;
		int serverPort;
		int publicTableCount;
		boolean flywaySchemaHistoryPresent;
		String comment;
		try {
			Class.forName("org.postgresql.Driver");
			try (Connection connection = DriverManager.getConnection(expectedUrl, datasourceUsername, datasourcePassword);
					Statement statement = connection.createStatement()) {
				try (ResultSet identity = statement.executeQuery("SELECT current_database(), inet_server_port()")) {
					if (!identity.next()) {
						throw new IllegalStateException("The disposable database did not identify itself");
					}
					currentDatabase = identity.getString(1);
					serverPort = identity.getInt(2);
				}
				try (ResultSet tables = statement.executeQuery("""
						SELECT COUNT(*) FROM pg_catalog.pg_class AS relation
						JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
						WHERE namespace.nspname = 'public' AND relation.relkind IN ('r', 'p', 'v', 'm')
						""")) {
					tables.next();
					publicTableCount = tables.getInt(1);
				}
				try (ResultSet history = statement.executeQuery(
						"SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")) {
					history.next();
					flywaySchemaHistoryPresent = history.getBoolean(1);
				}
				try (ResultSet marker = statement.executeQuery("""
						SELECT coalesce(pg_catalog.shobj_description(database.oid, 'pg_database'), '')
						FROM pg_database AS database
						WHERE database.datname = current_database()
						""")) {
					marker.next();
					comment = marker.getString(1);
				}
			}
		} catch (IllegalStateException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new IllegalStateException("Could not inspect the disposable database before context refresh", exception);
		}

		if (!database.equals(currentDatabase) || serverPort != 5432) {
			throw new IllegalStateException("Connected to " + currentDatabase + " on port " + serverPort);
		}
		if (publicTableCount != 0 || flywaySchemaHistoryPresent) {
			throw new IllegalStateException("The disposable database is not empty before context refresh");
		}
		if (!ownershipToken.equals(comment)) {
			throw new IllegalStateException("The disposable database ownership marker does not match this run");
		}

		String body = """
				{
				  "phase": "before-context-refresh",
				  "validatedBeforeContextRefresh": true,
				  "flywaySchemaHistoryPresent": false,
				  "publicTableCount": 0,
				  "currentDatabase": "%s",
				  "serverPort": 5432,
				  "datasourceUrl": "%s",
				  "flywayUrl": "%s",
				  "datasourceUsername": "%s",
				  "flywayUser": "%s",
				  "aisProperty": "app.ais.enabled",
				  "aisValue": "false",
				  "mailHost": "127.0.0.1",
				  "mailPort": "1025"
				}
				""".formatted(currentDatabase, datasourceUrl, flywayUrl, datasourceUsername, flywayUser);
		try {
			Path temporary = reportPath.resolveSibling(reportPath.getFileName() + ".partial");
			Files.writeString(temporary, body);
			Files.move(temporary, reportPath, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not record the pre-migration validation", exception);
		}
	}

	private static String required(ConfigurableEnvironment environment, String key) {
		String value = environment.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Missing " + key);
		}
		return value;
	}

	private static String databaseName(String url) {
		Matcher matcher = JDBC_URL.matcher(url);
		if (!matcher.matches() || "drift".equals(matcher.group(1)) || "drift_test".equals(matcher.group(1))) {
			throw new IllegalStateException("Refusing unexpected JDBC target");
		}
		return matcher.group(1);
	}

	private static Path reportPath(String report, String ownershipToken) {
		Path path = Path.of(report);
		if (!path.isAbsolute() || !path.getParent().equals(Path.of("/tmp"))
				|| !path.getFileName().toString().equals("drift-cdg83-guard-" + ownershipToken + ".json")) {
			throw new IllegalStateException("Refusing unexpected validation report path");
		}
		return path;
	}
}
