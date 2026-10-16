package com.drift.backend.account.admin;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAssignmentAuditRepository extends JpaRepository<UserAssignmentAudit, Long> {

	@EntityGraph(attributePaths = { "operator", "target" })
	@Query("""
			SELECT audit FROM UserAssignmentAudit audit
			WHERE audit.organisationId = :companyId OR audit.previousOrganisationId = :companyId
			ORDER BY audit.recordedAt DESC, audit.id DESC
			""")
	List<UserAssignmentAudit> findForCompany(@Param("companyId") Long companyId);
}
