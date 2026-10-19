package com.drift.backend.shipment;

import java.util.List;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRiskAlertDeliveryRepository extends JpaRepository<ShipmentRiskAlertDelivery, Long> {

	/** Call inside a transaction; PostgreSQL skips rows another worker already claimed. */
	@Query(value = """
			SELECT * FROM shipment_risk_alert_deliveries
			WHERE status IN ('PENDING', 'RETRYABLE_FAILURE')
				AND next_attempt_at <= :now
				AND (claim_until IS NULL OR claim_until <= :now)
			ORDER BY next_attempt_at, id
			FOR UPDATE SKIP LOCKED
			LIMIT :limit
			""", nativeQuery = true)
	List<ShipmentRiskAlertDelivery> lockDueForClaim(@Param("now") Instant now, @Param("limit") int limit);

	List<ShipmentRiskAlertDelivery> findByTransitionIdOrderByIdAsc(Long transitionId);
}
