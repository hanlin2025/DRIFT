package com.drift.backend.account.passwordreset;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;

@Service
public class PasswordResetStore {

	private final UserAccountRepository users;
	private final PasswordResetTokenRepository tokens;
	private final Duration ttl;

	public PasswordResetStore(UserAccountRepository users, PasswordResetTokenRepository tokens,
			@Value("${app.password-reset.ttl}") Duration ttl) {
		this.users = users;
		this.tokens = tokens;
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalStateException("app.password-reset.ttl must be positive");
		}
		this.ttl = ttl;
	}

	@Transactional
	public Optional<IssuedReset> issue(String email) {
		String rawToken = ResetTokens.generate();
		String hash = ResetTokens.hash(rawToken);
		UserAccount account = users.findByEmailIgnoreCase(email).orElse(null);
		if (account == null) {
			return Optional.empty();
		}
		PasswordResetToken saved = tokens.saveAndFlush(
				new PasswordResetToken(account, hash, Instant.now().plus(ttl)));
		return Optional.of(new IssuedReset(saved.getId(), account.getId(), rawToken));
	}

	@Transactional
	public void deleteOlderUnusedLinks(Long userId, Long keepId) {
		tokens.deleteOtherUnused(userId, keepId);
	}

	@Transactional
	public void discard(Long tokenId) {
		tokens.deleteByTokenId(tokenId);
	}

	public record IssuedReset(Long id, Long userId, String rawToken) {
	}
}
