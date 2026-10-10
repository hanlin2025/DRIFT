package com.drift.backend.shipment.csvimport;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentImportJobRepository extends JpaRepository<ShipmentImportJob, Long> {

	Optional<ShipmentImportJob> findByIdAndManagingCompanyId(Long id, Long managingCompanyId);
}
