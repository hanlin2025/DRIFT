package com.drift.backend.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

	boolean existsByEmailIgnoreCase(String email);

	boolean existsByCompany_Id(Long companyId);

	long countByCompany_Id(Long companyId);

	/** Locks the account so two signups for the same pending email are applied one after the other. */
	@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT account FROM UserAccount account WHERE LOWER(account.email) = LOWER(:email)")
	Optional<UserAccount> findForSignupReplacement(@Param("email") String email);

	@EntityGraph(attributePaths = "company")
	Optional<UserAccount> findByEmailIgnoreCase(String email);

	@Query("""
			SELECT COUNT(account) FROM UserAccount account
			WHERE account.company.id = :companyId
				AND account.id <> :excludedUserId
				AND account.role.code = com.drift.backend.account.Role.ADMIN
				AND account.status = com.drift.backend.account.AccountStatus.ACTIVE
			""")
	long countOtherActiveAdministrators(@Param("companyId") Long companyId, @Param("excludedUserId") Long excludedUserId);

	@Query("""
			SELECT new com.drift.backend.account.SessionState(account.sessionGeneration, account.status)
			FROM UserAccount account WHERE account.id = :id
			""")
	Optional<SessionState> findSessionStateById(@Param("id") Long id);

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
