package com.drift.backend.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.organisation.exception.DuplicateOrganisationException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrganisationCreationConcurrencyIntegrationTests {

	@Autowired OrganisationCreationService organisations;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private String email;
	private Long adminId;
	private String code;

	@BeforeEach
	void administrator() {
		email = "cdg313-race-" + UUID.randomUUID() + "@example.com";
		code = "CDG313R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG 313 Race', ?, ?, id, (SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO')
				FROM roles WHERE code = 'ADMIN'
				""", email, passwords.encode("Example123"));
		adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	@AfterEach
	void cleanup() {
		jdbc.update("DELETE FROM companies WHERE code = ?", code);
		jdbc.update("DELETE FROM users WHERE email = ?", email);
	}

	@Test
	void concurrentCreatesPersistOneOrganisation() throws Exception {
		AuthenticatedUser admin = new AuthenticatedUser(adminId, email, Role.ADMIN, Instant.now().plusSeconds(3600));
		CreateOrganisationRequest request = new CreateOrganisationRequest(code, "Pacific Gate Imports");
		ExecutorService workers = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger created = new AtomicInteger();
		AtomicInteger duplicates = new AtomicInteger();
		try {
			Future<?> first = workers.submit(() -> create(admin, request, start, created, duplicates));
			Future<?> second = workers.submit(() -> create(admin, request, start, created, duplicates));
			start.countDown();
			first.get(20, TimeUnit.SECONDS);
			second.get(20, TimeUnit.SECONDS);
		} finally {
			workers.shutdownNow();
		}

		assertThat(created.get()).isEqualTo(1);
		assertThat(duplicates.get()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = ?", Integer.class, code)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE company_id = (SELECT id FROM companies WHERE code = ?)",
				Integer.class, code)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM forwarder_importer_relationships
				WHERE forwarder_company_id = (SELECT id FROM companies WHERE code = ?)
				   OR importer_company_id = (SELECT id FROM companies WHERE code = ?)
				""", Integer.class, code, code)).isZero();
	}

	private void create(AuthenticatedUser admin, CreateOrganisationRequest request, CountDownLatch start,
			AtomicInteger created, AtomicInteger duplicates) {
		try {
			start.await();
			organisations.create(admin, request);
			created.incrementAndGet();
		} catch (DuplicateOrganisationException ex) {
			duplicates.incrementAndGet();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}
}
