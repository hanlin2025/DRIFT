package com.drift.backend.shipment;

import java.util.List;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ShipmentRiskAlertDeliveryRepository extends JpaRepository<ShipmentRiskAlertDelivery, Long> {

	@Query(value = """
			SELECT COUNT(*) FROM shipment_risk_alert_deliveries delivery
			JOIN shipment_risk_transitions transition ON transition.id = delivery.transition_id
			WHERE transition.activation_generation < :generation AND delivery.claim_until > :now
			""", nativeQuery = true)
	long countLiveClaimsForOlderGenerations(@Param("generation") long generation, @Param("now") Instant now);

	@Modifying
	@Query(value = """
			UPDATE shipment_risk_alert_deliveries delivery
			SET status = 'SUPPRESSED', suppression_reason = :reason, next_attempt_at = NULL,
				claim_token = NULL, claim_until = NULL, updated_at = :now
			FROM shipment_risk_transitions transition
			WHERE delivery.transition_id = transition.id
				AND transition.activation_generation < :generation
				AND delivery.status IN ('PENDING', 'RETRYABLE_FAILURE')
			""", nativeQuery = true)
	int suppressOlderGenerations(@Param("generation") long generation, @Param("reason") String reason,
			@Param("now") Instant now);

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

	/** Locks the complete delivery context while a sender establishes or finalizes a claim. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT delivery FROM ShipmentRiskAlertDelivery delivery
			JOIN FETCH delivery.transition transition
			JOIN FETCH transition.shipment shipment
			JOIN FETCH shipment.company
			LEFT JOIN FETCH shipment.importerCompany
			JOIN FETCH delivery.user recipient
			LEFT JOIN FETCH recipient.company
			JOIN FETCH recipient.role
			WHERE delivery.id = :deliveryId
			""")
	java.util.Optional<ShipmentRiskAlertDelivery> findByIdForDeliveryProcessing(@Param("deliveryId") Long deliveryId);
}
