package com.drift.backend.account.admin;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountStatusAuditRepository extends JpaRepository<AccountStatusAudit, Long> {

	@EntityGraph(attributePaths = { "operator", "target" })
	@Query("""
			SELECT audit FROM AccountStatusAudit audit
			WHERE audit.organisationId = :companyId
			ORDER BY audit.recordedAt DESC, audit.id DESC
			""")
	List<AccountStatusAudit> findForCompany(@Param("companyId") Long companyId);
}
