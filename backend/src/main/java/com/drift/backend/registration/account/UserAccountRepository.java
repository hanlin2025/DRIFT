package com.drift.backend.registration.account;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

	boolean existsByEmailIgnoreCase(String email);
}
