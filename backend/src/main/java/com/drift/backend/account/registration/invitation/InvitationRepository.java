package com.drift.backend.account.registration.invitation;

import java.util.List;
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

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select invitation from Invitation invitation
			where invitation.email = :email and invitation.status = com.drift.backend.account.registration.invitation.InvitationStatus.PENDING
			""")
	Optional<Invitation> findPendingForUpdate(@Param("email") String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select invitation from Invitation invitation where invitation.id = :id")
	Optional<Invitation> findForWithdrawal(@Param("id") Long id);

	@Query("""
			select invitation from Invitation invitation
			join fetch invitation.company
			where invitation.company.id = :companyId
			  and invitation.status = com.drift.backend.account.registration.invitation.InvitationStatus.PENDING
			order by invitation.expiresAt asc, invitation.id asc
			""")
	List<Invitation> findPendingByCompany(@Param("companyId") Long companyId);
}
