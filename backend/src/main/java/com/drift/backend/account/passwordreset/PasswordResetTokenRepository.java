package com.drift.backend.account.passwordreset;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from PasswordResetToken token where token.tokenHash = :tokenHash")
	Optional<PasswordResetToken> findForReset(@Param("tokenHash") String tokenHash);

	@Modifying(flushAutomatically = true)
	@Query("""
			delete from PasswordResetToken token
			where token.user.id = :userId and token.usedAt is null and token.id < :keepId
			""")
	int deleteOtherUnused(@Param("userId") Long userId, @Param("keepId") Long keepId);

	@Modifying(flushAutomatically = true)
	@Query("delete from PasswordResetToken token where token.id = :id")
	void deleteByTokenId(@Param("id") Long id);
}
