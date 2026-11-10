package com.drift.backend.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class ProductionMailConfigurationGuardTest {

	@Test
	void guardIsScopedToProductionProfile() {
		assertThat(ProductionMailConfigurationGuard.class.getAnnotation(Profile.class).value())
				.containsExactly("production");
	}

	@Test
	void acceptsConfiguredGmailAndHostedFrontend() {
		assertThatCode(() -> ProductionMailConfigurationGuard.validate("smtp.gmail.com", 587, "sender@gmail.com",
				"app-password", true, true, true, "sender@gmail.com", "https://drift.vercel.app",
				"https://drift.vercel.app/signup", "https://drift.vercel.app/reset-password",
				"https://drift.vercel.app"))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsMissingGmailCredentials() {
		assertThatThrownBy(() -> ProductionMailConfigurationGuard.validate("smtp.gmail.com", 587, "sender@gmail.com",
				" ", true, true, true, "sender@gmail.com", "https://drift.vercel.app",
				"https://drift.vercel.app/signup", "https://drift.vercel.app/reset-password",
				"https://drift.vercel.app"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Production mail requires MAIL_USERNAME and MAIL_PASSWORD");
	}

	@Test
	void rejectsLocalSmtpAndLocalLinks() {
		assertThatThrownBy(() -> ProductionMailConfigurationGuard.validate("localhost", 1025, "", "", false,
				false, false, "noreply@localhost", "http://localhost:5173", "http://localhost:5173/signup",
				"http://localhost:5173/reset-password", "http://localhost:5173"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Production mail must use smtp.gmail.com on port 587");
	}

	@Test
	void rejectsPerMessageLinksOnAnotherHost() {
		assertThatThrownBy(() -> ProductionMailConfigurationGuard.validate("smtp.gmail.com", 587, "sender@gmail.com",
				"app-password", true, true, true, "sender@gmail.com", "https://drift.vercel.app",
				"https://other.example/signup", "https://drift.vercel.app/reset-password",
				"https://drift.vercel.app"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("INVITATION_LINK_BASE_URL must use the FRONTEND_BASE_URL host in production");
	}

	@Test
	void acceptsAHostedSignupVerificationLink() {
		assertThatCode(() -> ProductionMailConfigurationGuard.validateSignup("https://drift.vercel.app",
				"https://drift.vercel.app/verify-email", "sender@gmail.com"))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsALocalOrForeignSignupVerificationLinkAndALocalSender() {
		assertThatThrownBy(() -> ProductionMailConfigurationGuard.validateSignup("https://drift.vercel.app",
				"http://localhost:5173/verify-email", "sender@gmail.com"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("SIGNUP_LINK_BASE_URL must be an absolute hosted HTTPS URL without query or fragment");
		assertThatThrownBy(() -> ProductionMailConfigurationGuard.validateSignup("https://drift.vercel.app",
				"https://other.example/verify-email", "sender@gmail.com"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("SIGNUP_LINK_BASE_URL must use the FRONTEND_BASE_URL host in production");
		assertThatThrownBy(() -> ProductionMailConfigurationGuard.validateSignup("https://drift.vercel.app",
				"https://drift.vercel.app/verify-email", "noreply@localhost"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Production mail requires a non-local sender for email verification");
	}
}
