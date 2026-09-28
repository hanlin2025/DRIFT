package com.drift.backend.shipment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

	boolean existsByCompanyIdAndShipmentReferenceIgnoreCase(Long companyId, String shipmentReference);
}
