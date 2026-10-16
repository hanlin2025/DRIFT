package com.drift.backend.account.admin;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountStatusAuditRepository extends JpaRepository<AccountStatusAudit, Long> {
}
