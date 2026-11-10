package com.drift.backend.account.signup;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanySignupAuditRepository extends JpaRepository<CompanySignupAudit, Long> {
}
