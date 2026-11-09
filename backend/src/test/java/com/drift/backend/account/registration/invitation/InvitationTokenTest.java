package com.drift.backend.account.registration.invitation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.drift.backend.account.admin.InvitationMailConfig;

class InvitationTokenTest {

	@Test
	void generatedCodesMatchTheSignupTokenShape() {
		String code = InvitationToken.generate();
		assertThat(code).hasSize(43).matches("[A-Za-z0-9_-]{43}");
		assertThat(InvitationToken.hash(code)).hasSize(64).isNotEqualTo(code);
	}

	@Test
	void theMailedLinkKeepsTheCodeInTheFragment() {
		String code = InvitationToken.generate();
		assertThat(InvitationMailConfig.link("http://localhost:5173/signup/", code))
				.isEqualTo("http://localhost:5173/signup#invite=" + code);
	}
}
