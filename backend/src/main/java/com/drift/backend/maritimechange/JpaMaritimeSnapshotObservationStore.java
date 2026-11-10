package com.drift.backend.maritimechange;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Component
class JpaMaritimeSnapshotObservationStore implements MaritimeSnapshotObservationStore {

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	public Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity) {
		return entityManager.createQuery("""
				SELECT observation FROM MaritimeSnapshotObservation observation
				WHERE observation.sourceType = :sourceType
					AND observation.sourceIdentity = :sourceIdentity
					AND observation.head = true
				""", MaritimeSnapshotObservation.class)
				.setParameter("sourceType", sourceType)
				.setParameter("sourceIdentity", sourceIdentity)
				.setMaxResults(1)
				.getResultStream()
				.findFirst();
	}

	@Override
	public Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String contentFingerprint) {
		return entityManager.createQuery("""
				SELECT observation FROM MaritimeSnapshotObservation observation
				WHERE observation.sourceType = :sourceType
					AND observation.sourceIdentity = :sourceIdentity
					AND observation.contentFingerprint = :contentFingerprint
				""", MaritimeSnapshotObservation.class)
				.setParameter("sourceType", sourceType)
				.setParameter("sourceIdentity", sourceIdentity)
				.setParameter("contentFingerprint", contentFingerprint)
				.setMaxResults(1)
				.getResultStream()
				.findFirst();
	}

	@Override
	public void add(MaritimeSnapshotObservation observation, boolean becomesHead) {
		if (becomesHead) {
			entityManager.createQuery("""
					UPDATE MaritimeSnapshotObservation observation
					SET observation.head = false
					WHERE observation.sourceType = :sourceType
						AND observation.sourceIdentity = :sourceIdentity
						AND observation.head = true
					""")
					.setParameter("sourceType", observation.getSourceType())
					.setParameter("sourceIdentity", observation.getSourceIdentity())
					.executeUpdate();
			observation.markHead();
		}
		entityManager.persist(observation);
		entityManager.flush();
	}

	@Override
	public List<MaritimeSnapshotObservation> after(Instant detectedSince, Instant afterRecordedAt, long afterId, int limit) {
		String jpql = afterRecordedAt == null ? """
				SELECT observation FROM MaritimeSnapshotObservation observation
				WHERE observation.recordedAt >= :detectedSince
				ORDER BY observation.recordedAt ASC, observation.id ASC
				""" : """
				SELECT observation FROM MaritimeSnapshotObservation observation
				WHERE observation.recordedAt >= :detectedSince
					AND (observation.recordedAt > :afterRecordedAt
						OR (observation.recordedAt = :afterRecordedAt AND observation.id > :afterId))
				ORDER BY observation.recordedAt ASC, observation.id ASC
				""";
		var query = entityManager.createQuery(jpql, MaritimeSnapshotObservation.class)
				.setParameter("detectedSince", detectedSince);
		if (afterRecordedAt != null) {
			query.setParameter("afterRecordedAt", afterRecordedAt).setParameter("afterId", afterId);
		}
		return query.setMaxResults(limit).getResultList();
	}
}
