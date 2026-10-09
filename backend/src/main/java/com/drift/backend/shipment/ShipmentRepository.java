package com.drift.backend.shipment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

	List<Shipment> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

	Optional<Shipment> findByIdAndCompanyId(Long id, Long companyId);

	@Query("""
			SELECT COUNT(shipment) > 0
			FROM Shipment shipment
			WHERE shipment.company.id = :companyId
				AND LOWER(shipment.shipmentReference) = LOWER(:shipmentReference)
			""")
	boolean existsByCompanyIdAndShipmentReferenceIgnoreCase(
			@Param("companyId") Long companyId,
			@Param("shipmentReference") String shipmentReference);

	@Query("""
			SELECT COUNT(shipment) > 0
			FROM Shipment shipment
			WHERE shipment.company.id = :companyId
				AND shipment.id <> :shipmentId
				AND LOWER(shipment.shipmentReference) = LOWER(:shipmentReference)
			""")
	boolean existsOtherByCompanyIdAndShipmentReferenceIgnoreCase(
			@Param("companyId") Long companyId,
			@Param("shipmentId") Long shipmentId,
			@Param("shipmentReference") String shipmentReference);

}
