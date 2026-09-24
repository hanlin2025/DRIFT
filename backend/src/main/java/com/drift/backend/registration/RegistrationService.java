package com.drift.backend.registration;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

	private final UserAccountRepository users;
	private final InvitationRepository invitations;
	private final PasswordEncoder passwordEncoder;

	public RegistrationService(
			UserAccountRepository users,
			InvitationRepository invitations,
			PasswordEncoder passwordEncoder) {
		this.users = users;
		this.invitations = invitations;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public RegisterResponse register(RegisterRequest request) {
		PasswordPolicy.check(request.password());

		String email = request.email();
		if (users.existsByEmail(email)) {
			throw new DuplicateAccountException();
		}

		Invitation invitation = invitations
				.findByEmailIgnoreCaseAndStatus(email, InvitationStatus.PENDING)
				.orElseThrow(IneligibleInvitationException::new);

		UserAccount account = new UserAccount(
				request.fullName(),
				email,
				passwordEncoder.encode(request.password()),
				invitation.getRole());
		try {
			account = users.saveAndFlush(account);
		} catch (DataIntegrityViolationException ex) {
			throw new DuplicateAccountException();
		}

		invitation.consume();
		invitations.save(invitation);

		return new RegisterResponse(account.getId(), account.getFullName(), account.getEmail());
	}
}
