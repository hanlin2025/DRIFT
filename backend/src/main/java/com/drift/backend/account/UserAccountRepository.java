package com.drift.backend.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

	boolean existsByEmailIgnoreCase(String email);

	@EntityGraph(attributePaths = "company")
	Optional<UserAccount> findByEmailIgnoreCase(String email);

	@Query("SELECT account.sessionGeneration FROM UserAccount account WHERE account.id = :id")
	Optional<Long> findSessionGenerationById(@Param("id") Long id);

	@EntityGraph(attributePaths = { "company", "role" })
	@Query("SELECT account FROM UserAccount account WHERE account.id = :id")
	Optional<UserAccount> findForPermissionCheck(@Param("id") Long id);

	@EntityGraph(attributePaths = { "company", "role" })
	@Query("SELECT account FROM UserAccount account WHERE account.id = :id")
	Optional<UserAccount> findForProfile(@Param("id") Long id);

	@EntityGraph(attributePaths = { "company", "role" })
	@Query("""
			SELECT account FROM UserAccount account
			WHERE account.company.id = :companyId
			ORDER BY account.fullName ASC, account.id ASC
			""")
	List<UserAccount> findAllForAssignment(@Param("companyId") Long companyId);

	@EntityGraph(attributePaths = { "company", "role" })
	@Query("""
			SELECT account FROM UserAccount account
			JOIN account.company company
			WHERE account.company.id IN :companyIds
				AND account.status = com.drift.backend.account.AccountStatus.ACTIVE
				AND company.active = true
			ORDER BY account.id
			""")
	List<UserAccount> findActiveUsersForCompanies(@Param("companyIds") java.util.Set<Long> companyIds);
}
