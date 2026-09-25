package com.drift.backend.account.authentication;

import java.time.Instant;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;

public record LoginResponse(
		String token,
		Instant expiresAt,
		Long id,
		String fullName,
		String email,
		Role role,
		CompanySummary company) {

	public static LoginResponse of(UserAccount account, IssuedSession session) {
		return new LoginResponse(session.token(), session.expiresAt(), account.getId(), account.getFullName(),
				account.getEmail(), account.getRole(), CompanySummary.of(account.getCompany()));
	}
}
