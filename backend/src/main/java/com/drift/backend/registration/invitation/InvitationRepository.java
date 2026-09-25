package com.drift.backend.registration.invitation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {
	Optional<Invitation> findByTokenHash(String tokenHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Invitation i where i.tokenHash = :tokenHash")
	Optional<Invitation> findForRegistration(@Param("tokenHash") String tokenHash);
}
