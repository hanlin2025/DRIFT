package com.drift.backend.shipment.csvimport;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentImportJobErrorRepository extends JpaRepository<ShipmentImportJobError, Long> {

	List<ShipmentImportJobError> findByJobIdOrderByRowNumberAscIdAsc(Long jobId);
}
