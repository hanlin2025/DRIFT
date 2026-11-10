package com.drift.backend.account;

public enum AccountStatus {
	ACTIVE,
	REMOVED,
	/** A self-service company administrator who has not verified their email yet. Not active. */
	PENDING_VERIFICATION
}
