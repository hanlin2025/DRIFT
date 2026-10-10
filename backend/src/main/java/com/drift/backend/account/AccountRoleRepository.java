package com.drift.backend.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRoleRepository extends JpaRepository<AccountRole, Long> {

	Optional<AccountRole> findByCode(Role code);
}
