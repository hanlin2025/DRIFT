package com.drift.backend.account.authentication;

import java.time.Instant;

public record IssuedSession(String token, Instant expiresAt) {
}
