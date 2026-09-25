package com.drift.backend.registration.account;

public record RegisterResponse(Long id, String fullName, String email, Role role) {
}
