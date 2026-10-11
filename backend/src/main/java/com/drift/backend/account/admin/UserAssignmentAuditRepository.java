package com.drift.backend.account.admin;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAssignmentAuditRepository extends JpaRepository<UserAssignmentAudit, Long> {

	@EntityGraph(attributePaths = { "operator", "target" })
	List<UserAssignmentAudit> findAllByOrderByRecordedAtDescIdDesc();
}
