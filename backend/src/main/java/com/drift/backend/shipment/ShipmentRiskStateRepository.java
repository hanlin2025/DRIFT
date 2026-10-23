package com.drift.backend.shipment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ShipmentRiskStateRepository extends JpaRepository<ShipmentRiskState, Long> {

	@Modifying
	@Query(value = """
			INSERT INTO shipment_risk_states
				(shipment_id, baseline_mode, last_transition_sequence, version, created_at, updated_at)
			SELECT shipment.id, 'SILENT_FIRST_VALID', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
			FROM shipments shipment WHERE shipment.id = :shipmentId
			ON CONFLICT (shipment_id) DO NOTHING
			""", nativeQuery = true)
	int initializeMissingSilently(@Param("shipmentId") Long shipmentId);

	/** Lock this row before comparing risk and allocating a transition sequence. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT state FROM ShipmentRiskState state WHERE state.shipmentId = :shipmentId")
	Optional<ShipmentRiskState> findByShipmentIdForUpdate(@Param("shipmentId") Long shipmentId);
}
