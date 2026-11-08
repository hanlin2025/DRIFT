package com.drift.backend.company;

import java.util.List;
import java.util.Optional;

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
}
