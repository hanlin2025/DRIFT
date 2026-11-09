package com.drift.backend.importerauthorisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;

@SpringBootTest
@ActiveProfiles("test")
class ImporterAuthorisationConcurrencyIntegrationTests {

	@Autowired ImporterAuthorisationService authorisations;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	@Test
	void concurrentGrantsAndEndsLeaveOneRow() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		Long forwarderId = insertCompany("CDG338F_" + suffix, "Forwarder " + suffix);
		Long importerId = insertCompany("CDG338I_" + suffix, "Importer " + suffix);
		Long adminId = createAdmin(forwarderId, "cdg338-race-" + suffix + "@example.com");
		AuthenticatedUser admin = new AuthenticatedUser(adminId, "cdg338-race-" + suffix + "@example.com", Role.ADMIN,
				Instant.now().plus(1, ChronoUnit.HOURS));
		try {
			runTogether(8, () -> authorisations.grant(admin, importerId));
			assertThat(count(forwarderId, importerId)).isEqualTo(1);
			assertThat(active(forwarderId, importerId)).isTrue();

			Long authorisationId = jdbc.queryForObject("""
					SELECT id FROM forwarder_importer_relationships
					WHERE forwarder_company_id = ? AND importer_company_id = ?
					""", Long.class, forwarderId, importerId);
			runTogether(8, () -> authorisations.end(admin, authorisationId));
			assertThat(count(forwarderId, importerId)).isEqualTo(1);
			assertThat(active(forwarderId, importerId)).isFalse();

			runTogether(4, () -> authorisations.grant(admin, importerId));
			runTogether(4, () -> {
				authorisations.grant(admin, importerId);
				authorisations.end(admin, authorisationId);
			});
			assertThat(count(forwarderId, importerId)).isEqualTo(1);
			assertThat(active(forwarderId, importerId)).isIn(true, false);
		} finally {
			jdbc.update("DELETE FROM forwarder_importer_relationships WHERE forwarder_company_id = ?", forwarderId);
			jdbc.update("DELETE FROM users WHERE id = ?", adminId);
			jdbc.update("DELETE FROM companies WHERE id IN (?, ?)", forwarderId, importerId);
		}
	}

	private void runTogether(int threads, Runnable action) throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(threads);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int index = 0; index < threads; index++) {
				futures.add(pool.submit(() -> {
					barrier.await();
					action.run();
					return null;
				}));
			}
			for (Future<?> future : futures) {
				future.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private Long insertCompany(String code, String name) {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, name);
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long createAdmin(Long companyId, String email) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 338', ?, ?, id, ?
				FROM roles WHERE code = 'ADMIN'
				""", email, passwords.encode("Example123"), companyId);
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	private Integer count(Long forwarderId, Long importerId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM forwarder_importer_relationships
				WHERE forwarder_company_id = ? AND importer_company_id = ?
				""", Integer.class, forwarderId, importerId);
	}

	private Boolean active(Long forwarderId, Long importerId) {
		return jdbc.queryForObject("""
				SELECT active FROM forwarder_importer_relationships
				WHERE forwarder_company_id = ? AND importer_company_id = ?
				""", Boolean.class, forwarderId, importerId);
	}
}
