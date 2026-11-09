package com.drift.backend.account.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SetupCredentialTest {

	private static final String CREDENTIAL = "test-only-setup-token-not-used-outside-tests";

	@Test
	void aCredentialShorterThan32BytesIsNotConfigured() {
		assertThat(SetupCredential.isConfigured(null)).isFalse();
		assertThat(SetupCredential.isConfigured("")).isFalse();
		assertThat(SetupCredential.isConfigured("too-short")).isFalse();
		assertThat(SetupCredential.isConfigured(CREDENTIAL)).isTrue();
	}

	@Test
	void comparisonAcceptsOnlyTheSameConfiguredCredential() {
		assertThat(SetupCredential.matches(CREDENTIAL, CREDENTIAL)).isTrue();
		assertThat(SetupCredential.matches(CREDENTIAL, CREDENTIAL + " ")).isFalse();
		assertThat(SetupCredential.matches(CREDENTIAL, "different-setup-token-not-the-real-one")).isFalse();
		assertThat(SetupCredential.matches(CREDENTIAL, "")).isFalse();
		assertThat(SetupCredential.matches(CREDENTIAL, null)).isFalse();
		assertThat(SetupCredential.matches("too-short", "too-short")).isFalse();
		assertThat(SetupCredential.matches(null, CREDENTIAL)).isFalse();
	}
}
