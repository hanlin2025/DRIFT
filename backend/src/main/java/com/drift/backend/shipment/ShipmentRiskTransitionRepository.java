package com.drift.backend.shipment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRiskTransitionRepository extends JpaRepository<ShipmentRiskTransition, Long> {

	List<ShipmentRiskTransition> findByShipmentIdOrderBySequenceAsc(Long shipmentId);
}
