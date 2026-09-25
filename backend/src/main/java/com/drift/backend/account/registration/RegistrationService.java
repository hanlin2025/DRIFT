package com.drift.backend.account.registration;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.exception.DuplicateAccountException;

@Service
public class RegistrationService {

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;

	public RegistrationService(UserAccountRepository users, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public RegisterResponse register(RegisterRequest request) {
		PasswordPolicy.check(request.password());

		String email = request.email();
		if (users.existsByEmail(email)) {
			throw new DuplicateAccountException();
		}

		UserAccount account = new UserAccount(
				request.fullName(),
				email,
				passwordEncoder.encode(request.password()),
				request.role());
		try {
			account = users.saveAndFlush(account);
		} catch (DataIntegrityViolationException ex) {
			throw new DuplicateAccountException();
		}

		return new RegisterResponse(account.getId(), account.getFullName(), account.getEmail(), account.getRole());
	}
}
