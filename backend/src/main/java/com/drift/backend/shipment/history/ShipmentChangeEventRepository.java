package com.drift.backend.shipment.history;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentChangeEventRepository extends JpaRepository<ShipmentChangeEvent, Long> {

	/** Newest first; the id breaks ties between changes recorded at the same instant. */
	@EntityGraph(attributePaths = { "actor", "fieldChanges" })
	List<ShipmentChangeEvent> findByShipmentIdOrderByRecordedAtDescIdDesc(Long shipmentId);
}
