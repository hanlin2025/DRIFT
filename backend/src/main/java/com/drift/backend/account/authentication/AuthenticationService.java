package com.drift.backend.account.authentication;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.exception.InvalidCredentialsException;
import com.drift.backend.account.exception.SessionEndedException;

@Service
public class AuthenticationService {

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final JwtSessionTokens tokens;
	private final String unknownPasswordHash;

	public AuthenticationService(UserAccountRepository users, PasswordEncoder passwordEncoder, JwtSessionTokens tokens) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.tokens = tokens;
		this.unknownPasswordHash = passwordEncoder.encode("unused-drift-credential");
	}

	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		UserAccount account = users.findByEmailIgnoreCase(request.email()).orElse(null);
		String hash = account == null ? unknownPasswordHash : account.getPasswordHash();
		if (account == null || !passwordEncoder.matches(request.password(), hash)) {
			throw new InvalidCredentialsException();
		}
		return LoginResponse.of(account, tokens.issue(account));
	}

	@Transactional(readOnly = true)
	public SessionView current(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		return SessionView.of(account, principal.expiresAt());
	}
}
