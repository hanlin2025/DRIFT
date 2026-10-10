package com.drift.backend.account.admin.exception;

public class AdminAssignmentConflictException extends RuntimeException {

	public static final String MESSAGE = "A company can have only one admin.";

	public AdminAssignmentConflictException() {
		super(MESSAGE);
	}
}
