package com.drift.backend.account.signup;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

	/** Locks the row so two concurrent uses of one token cannot both succeed. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from EmailVerificationToken token where token.tokenHash = :tokenHash")
	Optional<EmailVerificationToken> findForVerification(@Param("tokenHash") String tokenHash);

	@Modifying(flushAutomatically = true)
	@Query("delete from EmailVerificationToken token where token.user.id = :userId and token.usedAt is null")
	int deleteAllUnused(@Param("userId") Long userId);
}
