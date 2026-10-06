package com.drift.backend.account.passwordreset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

	@Modifying(flushAutomatically = true)
	@Query("""
			delete from PasswordResetToken token
			where token.user.id = :userId and token.usedAt is null and token.id <> :keepId
			""")
	int deleteOtherUnused(@Param("userId") Long userId, @Param("keepId") Long keepId);

	@Modifying(flushAutomatically = true)
	@Query("delete from PasswordResetToken token where token.id = :id")
	void deleteByTokenId(@Param("id") Long id);
}
