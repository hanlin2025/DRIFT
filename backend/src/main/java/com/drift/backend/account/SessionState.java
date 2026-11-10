package com.drift.backend.account;

/** The parts of an account the session filter checks on every authenticated request. */
public record SessionState(long sessionGeneration, AccountStatus status) {
}
