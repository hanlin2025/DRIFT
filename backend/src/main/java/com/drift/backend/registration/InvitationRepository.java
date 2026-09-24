package com.drift.backend.registration;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

	@Query("SELECT i FROM Invitation i WHERE LOWER(i.email) = :email AND i.status = :status")
	Optional<Invitation> findByEmailIgnoreCaseAndStatus(@Param("email") String email, @Param("status") InvitationStatus status);
}
