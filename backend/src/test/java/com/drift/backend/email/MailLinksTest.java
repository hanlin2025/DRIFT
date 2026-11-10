package com.drift.backend.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.drift.backend.account.admin.InvitationMailConfig;
import com.drift.backend.account.passwordreset.ResetTokens;

class MailLinksTest {

	@Test
	void aTrailingSlashOnTheFrontendOriginDoesNotDoubleThePath() {
		assertThat(InvitationMailConfig.link("https://drift.example//signup", "invite code"))
				.isEqualTo("https://drift.example/signup#invite=invite+code");
		assertThat(ResetTokens.link("https://drift.example//reset-password/", "reset token"))
				.isEqualTo("https://drift.example/reset-password#token=reset+token");
	}

	@Test
	void localMailpitLinksStayOnLocalhost() {
		assertThat(InvitationMailConfig.link("http://localhost:5173/signup", "code"))
				.isEqualTo("http://localhost:5173/signup#invite=code");
		assertThat(ResetTokens.link("http://localhost:5173/reset-password", "token"))
				.isEqualTo("http://localhost:5173/reset-password#token=token");
	}
}
