package com.drift.backend.company;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ForwarderImporterRelationshipRepository extends JpaRepository<ForwarderImporterRelationship, Long> {

	boolean existsByForwarderCompanyIdAndImporterCompanyIdAndActiveTrue(Long forwarderCompanyId, Long importerCompanyId);

	Optional<ForwarderImporterRelationship> findByForwarderCompanyIdAndImporterCompanyId(
			Long forwarderCompanyId, Long importerCompanyId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select relationship from ForwarderImporterRelationship relationship
			where relationship.forwarderCompany.id = :forwarderCompanyId
			  and relationship.importerCompany.id = :importerCompanyId
			""")
	Optional<ForwarderImporterRelationship> findByPairForUpdate(@Param("forwarderCompanyId") Long forwarderCompanyId,
			@Param("importerCompanyId") Long importerCompanyId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select relationship from ForwarderImporterRelationship relationship
			where relationship.id = :id
			""")
	Optional<ForwarderImporterRelationship> findByIdForUpdate(@Param("id") Long id);

	@Query("""
			select relationship from ForwarderImporterRelationship relationship
			join fetch relationship.importerCompany importer
			where relationship.forwarderCompany.id = :forwarderCompanyId
			  and relationship.active = true
			  and importer.active = true
			order by importer.name asc, importer.id asc
			""")
	List<ForwarderImporterRelationship> findActiveByForwarderCompanyId(@Param("forwarderCompanyId") Long forwarderCompanyId);

	@Query(value = """
			SELECT importer FROM ForwarderImporterRelationship relationship
			JOIN relationship.importerCompany importer
			WHERE relationship.forwarderCompany.id = :companyId
			  AND relationship.active = true
			  AND importer.active = true
			  AND (:name = '' OR LOCATE(LOWER(:name), LOWER(importer.name)) > 0)
			ORDER BY importer.name ASC, importer.id ASC
			""", countQuery = """
			SELECT COUNT(importer) FROM ForwarderImporterRelationship relationship
			JOIN relationship.importerCompany importer
			WHERE relationship.forwarderCompany.id = :companyId
			  AND relationship.active = true
			  AND importer.active = true
			  AND (:name = '' OR LOCATE(LOWER(:name), LOWER(importer.name)) > 0)
			""")
	Page<Company> findActiveAuthorisedImporters(@Param("companyId") Long companyId, @Param("name") String name,
			Pageable pageable);
}
