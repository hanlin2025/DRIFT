package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.shipment.Shipment;
import com.drift.backend.shipment.ShipmentLinkService;
import com.drift.backend.shipment.ShipmentService;
import com.drift.backend.shipment.SyntheticPortCatalogTestConfiguration;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;

/** PostgreSQL regressions for independent assessment-history and importer-reassignment transactions. */
@SpringBootTest(properties = { "app.maritime-ai.enabled=true", "app.maritime-context.demo-enabled=false",
		"app.ais.enabled=false", "app.maritime-ai.controlled-replay-stub-enabled=false" })
@ActiveProfiles("test")
@Import({ MaritimeAiCrossShipmentEvaluationIntegrationTests.CrossShipmentConfiguration.class,
		SyntheticPortCatalogTestConfiguration.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MaritimeAiAssessmentHistoryConcurrencyIntegrationTests {
	private static final String PORT = "Synthetic Multi-source Port";
	private static final OffsetDateTime MOTHER_ARRIVAL = OffsetDateTime.parse("2026-10-01T05:00:00+08:00");
	private static final OffsetDateTime FEEDER_DEPARTURE = OffsetDateTime.parse("2026-10-01T14:00:00+08:00");
	private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

	@Autowired EntityManager entityManager;
	@Autowired CompanyRepository companies;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired ShipmentLinkService shipmentLinks;
	@Autowired ShipmentService shipments;
	@Autowired MaritimeAiAssessmentService assessments;
	@Autowired MaritimeAiCrossShipmentEvaluationIntegrationTests.ContextDerivedClient client;

	@BeforeEach
	void clearOfflineClient() {
		client.clear();
	}

	@Test
	void historyReadHoldsPostgresShareLockUntilReassignmentCommits() throws Exception {
		Fixture fixture = fixture();
		String assessmentId = generateForImporter(fixture);
		CountDownLatch readHasRowsAndHoldsTransaction = new CountDownLatch(1);
		CountDownLatch releaseHistoryTransaction = new CountDownLatch(1);
		CountDownLatch writerTransactionStarted = new CountDownLatch(1);
		AtomicInteger readerBackendPid = new AtomicInteger();
		AtomicInteger writerBackendPid = new AtomicInteger();
		ExecutorService workers = Executors.newFixedThreadPool(2);
		Future<List<MaritimeAiAssessmentResponse>> historyRead = null;
		Future<Integer> reassignment = null;
		try {
			historyRead = workers.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
				readerBackendPid.set(backendPid());
				List<MaritimeAiAssessmentResponse> rows = assessments.list(fixture.importerA(), fixture.shipmentId());
				readHasRowsAndHoldsTransaction.countDown();
				awaitLatch(releaseHistoryTransaction, WAIT_LIMIT, "history transaction release");
				return rows;
			}));
			assertThat(readHasRowsAndHoldsTransaction.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

			reassignment = workers.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
				writerBackendPid.set(backendPid());
				writerTransactionStarted.countDown();
				shipmentLinks.link(fixture.manager(), fixture.shipmentId(), fixture.importerBCompanyId());
				return writerBackendPid.get();
			}));
			assertThat(writerTransactionStarted.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
			assertThat(readerBackendPid.get()).isNotEqualTo(writerBackendPid.get());
			assertThat(awaitPostgresRowLockWait(writerBackendPid.get(), WAIT_LIMIT))
					.as("the importer reassignment must be waiting on the reader's PostgreSQL row lock")
					.isTrue();
			assertThat(reassignment.isDone()).isFalse();
			assertThat(currentImporter(fixture.shipmentId())).isEqualTo(fixture.importerACompanyId());

			// The read is authorized at its locked assignment snapshot; reassignment cannot commit until it ends.
			releaseHistoryTransaction.countDown();
			List<MaritimeAiAssessmentResponse> rowsReadBeforeReassignment = historyRead.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
			assertThat(rowsReadBeforeReassignment).extracting(MaritimeAiAssessmentResponse::assessmentId).containsExactly(assessmentId);
			reassignment.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
			assertThat(currentImporter(fixture.shipmentId())).isEqualTo(fixture.importerBCompanyId());

			assertThatThrownBy(() -> assessments.list(fixture.importerA(), fixture.shipmentId()))
					.isInstanceOf(ShipmentDetailForbiddenException.class);
			assertThat(assessments.list(fixture.importerB(), fixture.shipmentId())).isEmpty();
			assertThat(assessments.list(fixture.manager(), fixture.shipmentId()))
					.extracting(MaritimeAiAssessmentResponse::assessmentId).containsExactly(assessmentId);
		} finally {
			releaseHistoryTransaction.countDown();
			workers.shutdownNow();
			workers.awaitTermination(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	@Test
	void reassignmentDuringGenerationDeniesResponseAndRollsBackPersistedAssessment() throws Exception {
		Fixture fixture = fixture();
		String preexistingAssessmentId = generateForImporter(fixture);
		long baselineCount = assessmentCount(fixture.shipmentId());
		MaritimeAiCrossShipmentEvaluationIntegrationTests.ContextDerivedClient.GenerationGate gate = client.blockNextGeneration();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<MaritimeAiAssessmentResponse> generation = worker.submit(() -> assessments.generate(fixture.importerA(), fixture.shipmentId()));
			assertThat(gate.awaitEntered(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
			assertThat(gate.transactionActive()).as("generation is paused inside its service transaction").isTrue();
			int generationBackendPid = gate.backendPid();

			int reassignmentBackendPid = new TransactionTemplate(transactionManager).execute(status -> {
				int pid = backendPid();
				shipmentLinks.link(fixture.manager(), fixture.shipmentId(), fixture.importerBCompanyId());
				return pid;
			});
			assertThat(reassignmentBackendPid).isNotEqualTo(generationBackendPid);
			assertThat(currentImporter(fixture.shipmentId())).isEqualTo(fixture.importerBCompanyId());
			assertThat(assessmentCount(fixture.shipmentId())).isEqualTo(baselineCount);

			gate.release();
			ExecutionException deniedGeneration = assertThrows(ExecutionException.class,
					() -> generation.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS));
			assertThat(deniedGeneration.getCause()).isInstanceOf(ShipmentDetailForbiddenException.class);

			VerificationSnapshot afterRollback = new TransactionTemplate(transactionManager).execute(status ->
					new VerificationSnapshot(backendPid(), currentImporter(fixture.shipmentId()), assessmentCount(fixture.shipmentId()),
							assessmentIds(fixture.shipmentId())));
			assertThat(afterRollback.backendPid()).isNotEqualTo(generationBackendPid).isNotEqualTo(reassignmentBackendPid);
			assertThat(afterRollback.importerCompanyId()).isEqualTo(fixture.importerBCompanyId());
			assertThat(afterRollback.assessmentCount()).isEqualTo(baselineCount);
			assertThat(afterRollback.assessmentIds()).containsExactly(preexistingAssessmentId);

			assertThatThrownBy(() -> assessments.list(fixture.importerA(), fixture.shipmentId()))
					.isInstanceOf(ShipmentDetailForbiddenException.class);
			assertThat(assessments.list(fixture.importerB(), fixture.shipmentId())).isEmpty();
			assertThat(assessments.list(fixture.manager(), fixture.shipmentId()))
					.extracting(MaritimeAiAssessmentResponse::assessmentId).containsExactly(preexistingAssessmentId);
		} finally {
			gate.release();
			worker.shutdownNow();
			worker.awaitTermination(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	private Fixture fixture() {
		Fixture fixture = new TransactionTemplate(transactionManager).execute(status -> {
			Company managerCompany = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
			Company importerACompany = companies.findByCode("STRAITS_FRESH_DEMO").orElseThrow();
			String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
			String importerBCode = "CDG289_CONCURRENCY_" + suffix;
			jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", importerBCode, "CDG-289 " + suffix);
			Company importerBCompany = companies.findByCode(importerBCode).orElseThrow();

			AccountRole forwarderRole = accountRole(Role.FREIGHT_FORWARDER);
			AccountRole importerRole = accountRole(Role.IMPORTER);
			UserAccount managerAccount = new UserAccount("CDG-289 concurrency manager", "cdg289-manager-" + suffix + "@example.com",
					"offline-test-hash", forwarderRole, managerCompany);
			UserAccount importerAAccount = new UserAccount("CDG-289 importer A", "cdg289-importer-a-" + suffix + "@example.com",
					"offline-test-hash", importerRole, importerACompany);
			UserAccount importerBAccount = new UserAccount("CDG-289 importer B", "cdg289-importer-b-" + suffix + "@example.com",
					"offline-test-hash", importerRole, importerBCompany);
			entityManager.persist(managerAccount);
			entityManager.persist(importerAAccount);
			entityManager.persist(importerBAccount);
			entityManager.flush();

			Shipment shipment = new Shipment(managerCompany, managerAccount, "CDG289-CONCURRENCY-" + suffix,
					"Synthetic origin", "Synthetic destination", PORT, "Synthetic Multi-source Vessel", MOTHER_ARRIVAL,
					"Synthetic Multi-source Feeder", FEEDER_DEPARTURE);
			entityManager.persist(shipment);
			entityManager.flush();
			Long shipmentId = shipment.getId();
			AuthenticatedUser manager = authenticated(managerAccount, Role.FREIGHT_FORWARDER);
			AuthenticatedUser importerA = authenticated(importerAAccount, Role.IMPORTER);
			AuthenticatedUser importerB = authenticated(importerBAccount, Role.IMPORTER);
			shipmentLinks.link(manager, shipmentId, importerACompany.getId());
			return new Fixture(shipmentId, managerCompany.getId(), importerACompany.getId(), importerBCompany.getId(),
					manager, importerA, importerB);
		});
		return java.util.Objects.requireNonNull(fixture);
	}

	private AccountRole accountRole(Role role) {
		return entityManager.createQuery("SELECT role FROM AccountRole role WHERE role.code = :code", AccountRole.class)
				.setParameter("code", role).getSingleResult();
	}

	private static AuthenticatedUser authenticated(UserAccount account, Role role) {
		return new AuthenticatedUser(account.getId(), account.getEmail(), role, Instant.now().plus(Duration.ofHours(1)), account.getSessionGeneration());
	}

	private String generateForImporter(Fixture fixture) {
		MaritimeAiAssessmentResponse response = assessments.generate(fixture.importerA(), fixture.shipmentId());
		assertThat(response.state()).isEqualTo("GENERATED");
		return response.assessmentId();
	}

	private int backendPid() {
		return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
	}

	private Long currentImporter(Long shipmentId) {
		return jdbc.queryForObject("SELECT importer_company_id FROM shipments WHERE id = ?", Long.class, shipmentId);
	}

	private long assessmentCount(Long shipmentId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE shipment_id = ?", Long.class, shipmentId);
	}

	private List<String> assessmentIds(Long shipmentId) {
		return jdbc.queryForList("SELECT assessment_id FROM maritime_ai_assessments WHERE shipment_id = ? ORDER BY assessment_id",
				String.class, shipmentId);
	}

	private boolean awaitPostgresRowLockWait(int backendPid, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		CountDownLatch pollDelay = new CountDownLatch(1);
		do {
			String waitType = jdbc.queryForObject("SELECT COALESCE(wait_event_type, '') FROM pg_stat_activity WHERE pid = ?",
					String.class, backendPid);
			Long ungrantedLocks = jdbc.queryForObject("SELECT COUNT(*) FROM pg_locks WHERE pid = ? AND granted = FALSE",
					Long.class, backendPid);
			if ("Lock".equals(waitType) && ungrantedLocks != null && ungrantedLocks > 0) return true;
			if (System.nanoTime() >= deadline) return false;
			pollDelay.await(10, TimeUnit.MILLISECONDS);
		} while (true);
	}

	private static void awaitLatch(CountDownLatch latch, Duration timeout, String description) {
		try {
			if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IllegalStateException("Timed out waiting for " + description + ".");
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for " + description + ".", ex);
		}
	}

	private record Fixture(Long shipmentId, Long managerCompanyId, Long importerACompanyId, Long importerBCompanyId,
			AuthenticatedUser manager, AuthenticatedUser importerA, AuthenticatedUser importerB) {
	}

	private record VerificationSnapshot(int backendPid, Long importerCompanyId, long assessmentCount, List<String> assessmentIds) {
	}
}
