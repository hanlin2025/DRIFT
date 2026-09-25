package com.drift.backend.account.authentication;

import java.time.Instant;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;

public record SessionView(
		Instant expiresAt,
		Long id,
		String fullName,
		String email,
		Role role,
		CompanySummary company) {

	public static SessionView of(UserAccount account, Instant expiresAt) {
		return new SessionView(expiresAt, account.getId(), account.getFullName(), account.getEmail(),
				account.getRole(), CompanySummary.of(account.getCompany()));
	}
}
