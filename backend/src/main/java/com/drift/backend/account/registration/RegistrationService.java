package com.drift.backend.account.registration;

import java.time.Instant;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.drift.backend.account.registration.RegisterRequest;
import com.drift.backend.account.registration.RegisterResponse;
import com.drift.backend.account.AccountRole;
import com.drift.backend.account.AccountRoleRepository;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.exception.DuplicateAccountException;
import com.drift.backend.account.exception.IneligibleInvitationException;
import com.drift.backend.account.registration.invitation.Invitation;
import com.drift.backend.account.registration.invitation.InvitationRepository;
import com.drift.backend.account.registration.invitation.InvitationResponse;
import com.drift.backend.account.registration.invitation.InvitationToken;

@Service
public class RegistrationService {
	private final UserAccountRepository users;
	private final AccountRoleRepository roles;
	private final InvitationRepository invitations;
	private final PasswordEncoder passwordEncoder;

	public RegistrationService(UserAccountRepository users, AccountRoleRepository roles, InvitationRepository invitations,
			PasswordEncoder passwordEncoder) {
		this.users = users;
		this.roles = roles;
		this.invitations = invitations;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional(readOnly = true)
	public InvitationResponse resolveInvitation(String token) {
		Invitation invitation = invitations.findByTokenHash(InvitationToken.hash(token))
				.orElseThrow(IneligibleInvitationException::new);
		checkEligibility(invitation);
		return new InvitationResponse(invitation.getEmail(), invitation.getCompany().getName(),
				invitation.getRole(), invitation.getExpiresAt());
	}

	@Transactional
	public RegisterResponse register(RegisterRequest request) {
		PasswordPolicy.check(request.password());
		// Lock the invitation so concurrent requests cannot claim it twice.
		Invitation invitation = invitations.findForRegistration(InvitationToken.hash(request.invitationToken()))
				.orElseThrow(IneligibleInvitationException::new);
		checkEligibility(invitation);
		if (!invitation.getEmail().equals(request.email())) {
			throw new IneligibleInvitationException();
		}
		if (users.existsByEmailIgnoreCase(request.email())) {
			throw new DuplicateAccountException();
		}
		AccountRole role = roles.findByCode(invitation.getRole())
				.orElseThrow(() -> new IllegalStateException("Role is not configured"));
		UserAccount account = new UserAccount(request.fullName(), request.email(),
				passwordEncoder.encode(request.password()), role, invitation.getCompany());
		try {
			account = users.saveAndFlush(account);
		} catch (DataIntegrityViolationException ex) {
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof ConstraintViolationException violation
						&& ("users_email_key".equals(violation.getConstraintName())
						|| "users_email_lower_key".equals(violation.getConstraintName()))) {
					throw new DuplicateAccountException();
				}
			}
			throw ex;
		}
		// This update commits with the new account, or both changes roll back.
		invitation.consume(Instant.now());
		return new RegisterResponse(account.getId(), account.getFullName(), account.getEmail(), account.getRole());
	}

	private void checkEligibility(Invitation invitation) {
		if (!invitation.isEligible(Instant.now())) {
			throw new IneligibleInvitationException();
		}
	}
}
