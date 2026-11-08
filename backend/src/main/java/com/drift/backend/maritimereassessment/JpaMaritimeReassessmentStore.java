package com.drift.backend.maritimereassessment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

@Component
class JpaMaritimeReassessmentStore implements MaritimeReassessmentStore {

	private final EntityManager entityManager;
	private final JdbcTemplate jdbc;

	JpaMaritimeReassessmentStore(EntityManager entityManager, JdbcTemplate jdbc) {
		this.entityManager = entityManager;
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public <T> T exclusively(long shipmentId, String bundleHash, Supplier<T> work) {
		jdbc.query("SELECT pg_advisory_xact_lock(?)", result -> {
		}, lockKey(shipmentId, bundleHash));
		return work.get();
	}

	@Override
	public List<StoredMaritimeReassessment> forBundle(long shipmentId, String bundleHash) {
		return entityManager.createQuery("""
				SELECT attempt FROM MaritimeReassessmentAttempt attempt
				WHERE attempt.shipmentId = :shipmentId AND attempt.bundleHash = :bundleHash
				ORDER BY attempt.id ASC
				""", MaritimeReassessmentAttempt.class)
				.setParameter("shipmentId", shipmentId)
				.setParameter("bundleHash", bundleHash)
				.getResultList()
				.stream()
				.map(MaritimeReassessmentAttempt::stored)
				.toList();
	}

	@Override
	public StoredMaritimeReassessment insert(MaritimeReassessmentAttempt attempt) {
		entityManager.persist(attempt);
		entityManager.flush();
		return attempt.stored();
	}

	@Override
	public List<StoredMaritimeReassessment> history(long shipmentId) {
		return entityManager.createQuery("""
				SELECT attempt FROM MaritimeReassessmentAttempt attempt
				WHERE attempt.shipmentId = :shipmentId
				ORDER BY attempt.createdAt DESC, attempt.id DESC
				""", MaritimeReassessmentAttempt.class)
				.setParameter("shipmentId", shipmentId)
				.getResultList()
				.stream()
				.map(MaritimeReassessmentAttempt::stored)
				.toList();
	}

	private static long lockKey(long shipmentId, String bundleHash) {
		return UUID.nameUUIDFromBytes((shipmentId + ":" + bundleHash).getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
	}
}
