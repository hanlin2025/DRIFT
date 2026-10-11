package com.drift.backend.company;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ForwarderImporterRelationshipRepository extends JpaRepository<ForwarderImporterRelationship, Long> {

	boolean existsByForwarderCompanyIdAndImporterCompanyIdAndActiveTrue(Long forwarderCompanyId, Long importerCompanyId);

	Optional<ForwarderImporterRelationship> findByForwarderCompanyIdAndImporterCompanyId(
			Long forwarderCompanyId, Long importerCompanyId);
}
