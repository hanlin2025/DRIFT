package com.drift.backend.account.admin;

import java.time.Duration;

public interface InvitationMailer {

	void sendInvitation(String email, String link, String code, String organisationName, String roleLabel, Duration validFor);
}
