package com.drift.backend.account.registration.invitation;

import java.time.Instant;
import com.drift.backend.account.Role;

public record InvitationResponse(String email, String companyName, Role role, Instant expiresAt) { }
