package com.drift.backend.account.authentication;

import java.time.Instant;

import com.drift.backend.account.Role;

public record AuthenticatedUser(Long id, String email, Role role, Instant expiresAt, long sessionGeneration) {

	public AuthenticatedUser(Long id, String email, Role role, Instant expiresAt) {
		this(id, email, role, expiresAt, 0);
	}
}
