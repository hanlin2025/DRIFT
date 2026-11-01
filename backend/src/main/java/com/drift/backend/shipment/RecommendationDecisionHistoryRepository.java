package com.drift.backend.shipment;

import java.util.List;

/** Insertion and shipment history reads. Update and delete are not available. */
public interface RecommendationDecisionHistoryRepository {

	RecommendationDecisionHistory insert(RecommendationDecisionHistory history);

	List<RecommendationDecisionHistory> findForShipmentNewestFirst(long shipmentId);
}
