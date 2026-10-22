package com.drift.backend.shipment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ShipmentRiskTransitionRepository extends JpaRepository<ShipmentRiskTransition, Long> {

	List<ShipmentRiskTransition> findByShipmentIdOrderBySequenceAsc(Long shipmentId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT transition FROM ShipmentRiskTransition transition WHERE transition.id = :transitionId")
	java.util.Optional<ShipmentRiskTransition> findByIdForRecipientResolution(@Param("transitionId") Long transitionId);
}
