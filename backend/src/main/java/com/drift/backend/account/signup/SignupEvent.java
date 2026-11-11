package com.drift.backend.account.signup;

public enum SignupEvent {
	REGISTERED,
	EMAIL_VERIFIED,
	/** A newer signup for the same pending email replaced the earlier submission. */
	RESUBMITTED
}
