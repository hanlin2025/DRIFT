package com.drift.backend.shipment;

import java.util.List;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Repository
class RecommendationDecisionHistoryStore implements RecommendationDecisionHistoryRepository {

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	public RecommendationDecisionHistory insert(RecommendationDecisionHistory history) {
		if (history == null) {
			throw new IllegalArgumentException("History row is required");
		}
		if (history.getId() != null) {
			throw new IllegalArgumentException("History rows are inserted once");
		}
		entityManager.persist(history);
		entityManager.flush();
		return history;
	}

	@Override
	public List<RecommendationDecisionHistory> findForShipmentNewestFirst(long shipmentId) {
		return entityManager.createQuery("""
				SELECT history FROM RecommendationDecisionHistory history
				JOIN FETCH history.decidedBy
				WHERE history.shipment.id = :shipmentId
				ORDER BY history.decidedAt DESC, history.id DESC
				""", RecommendationDecisionHistory.class)
				.setParameter("shipmentId", shipmentId)
				.getResultList();
	}
}
