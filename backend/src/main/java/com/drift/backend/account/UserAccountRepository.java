package com.drift.backend.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

	boolean existsByEmailIgnoreCase(String email);

	@EntityGraph(attributePaths = "company")
	Optional<UserAccount> findByEmailIgnoreCase(String email);
}
