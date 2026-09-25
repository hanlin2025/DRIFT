package com.drift.backend.account.registration;

import com.drift.backend.account.Role;

public record RegisterResponse(Long id, String fullName, String email, Role role) {
}
