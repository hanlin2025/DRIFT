package com.drift.backend.shipment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentImporterConstraintRepository
		extends JpaRepository<ShipmentImporterConstraint, ShipmentImporterConstraintId> {

	Optional<ShipmentImporterConstraint> findByShipmentIdAndImporterCompanyId(Long shipmentId, Long importerCompanyId);
}
