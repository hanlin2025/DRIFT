package com.drift.backend.registration.invitation;

import java.time.Instant;
import com.drift.backend.registration.account.Role;

public record InvitationResponse(String email, String companyName, Role role, Instant expiresAt) { }
