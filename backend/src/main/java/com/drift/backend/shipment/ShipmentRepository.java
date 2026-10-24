package com.drift.backend.shipment;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ShipmentRepository extends JpaRepository<Shipment, Long>, JpaSpecificationExecutor<Shipment> {

	@Query("SELECT shipment.id FROM Shipment shipment WHERE shipment.status = com.drift.backend.shipment.ShipmentStatus.ACTIVE "
			+ "AND shipment.id > :afterId ORDER BY shipment.id")
	List<Long> findActiveIdsAfter(@Param("afterId") Long afterId, Pageable pageable);

	/** Locks one shipment while its current schedule and lifecycle status are evaluated. */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("SELECT shipment FROM Shipment shipment WHERE shipment.id = :shipmentId")
	Optional<Shipment> findByIdForRiskEvaluation(@Param("shipmentId") Long shipmentId);

	@Query("""
			SELECT shipment FROM Shipment shipment
			WHERE (shipment.company.id = :companyId OR shipment.importerCompany.id = :companyId)
				AND shipment.status = com.drift.backend.shipment.ShipmentStatus.ACTIVE
			ORDER BY shipment.createdAt DESC
			""")
	List<Shipment> findVisibleByCompanyIdOrderByCreatedAtDesc(@Param("companyId") Long companyId);

	Optional<Shipment> findByIdAndCompanyId(Long id, Long companyId);

	@Query("""
			SELECT shipment FROM Shipment shipment
			WHERE shipment.id = :shipmentId
				AND (shipment.company.id = :companyId OR shipment.importerCompany.id = :companyId)
			""")
	Optional<Shipment> findByIdVisibleToCompanyId(
			@Param("shipmentId") Long shipmentId,
			@Param("companyId") Long companyId);

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
			SELECT LOWER(shipment.shipmentReference)
			FROM Shipment shipment
			WHERE shipment.company.id = :companyId
				AND LOWER(shipment.shipmentReference) IN :shipmentReferences
			""")
	Set<String> findExistingShipmentReferencesByCompanyIdIgnoringCase(
			@Param("companyId") Long companyId,
			@Param("shipmentReferences") Set<String> shipmentReferences);

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
