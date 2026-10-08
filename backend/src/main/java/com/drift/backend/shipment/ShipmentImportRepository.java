package com.drift.backend.shipment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentImportRepository extends JpaRepository<ShipmentImport, Long> {

	Optional<ShipmentImport> findByIdAndCompanyId(Long id, Long companyId);
}
