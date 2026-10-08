package com.drift.backend.shipment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

	List<Shipment> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

	@Query("""
			SELECT shipment
			FROM Shipment shipment
			WHERE shipment.company.id = :companyId OR shipment.importerCompany.id = :companyId
			ORDER BY shipment.createdAt DESC
			""")
	List<Shipment> findVisibleToCompany(@Param("companyId") Long companyId);

	Optional<Shipment> findByIdAndCompanyId(Long id, Long companyId);

	@Query("""
			SELECT shipment
			FROM Shipment shipment
			WHERE shipment.id = :id
				AND (shipment.company.id = :companyId OR shipment.importerCompany.id = :companyId)
			""")
	Optional<Shipment> findVisibleByIdAndCompanyId(@Param("id") Long id, @Param("companyId") Long companyId);

	@Query("""
			SELECT COUNT(shipment) > 0
			FROM Shipment shipment
			WHERE shipment.company.id = :companyId
				AND LOWER(shipment.shipmentReference) = LOWER(:shipmentReference)
			""")
	boolean existsByCompanyIdAndShipmentReferenceIgnoreCase(
			@Param("companyId") Long companyId,
			@Param("shipmentReference") String shipmentReference);
}
