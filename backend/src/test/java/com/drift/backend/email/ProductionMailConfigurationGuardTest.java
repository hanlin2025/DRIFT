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
	void rejectsMissingGmailCredentialsWithoutRevealingThePassword() {
		String appPassword = "super-secret-app-password";
		assertThatThrownBy(() -> ProductionMailConfigurationGuard.validate("smtp.gmail.com", 587, " ",
				appPassword, true, true, true, "sender@gmail.com", "https://drift.vercel.app",
				"https://drift.vercel.app/signup", "https://drift.vercel.app/reset-password",
				"https://drift.vercel.app"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Production mail requires MAIL_USERNAME")
				.hasMessageNotContaining(appPassword);
		assertThatThrownBy(() -> ProductionMailConfigurationGuard.validate("smtp.gmail.com", 587, "sender@gmail.com",
				" ", true, true, true, "sender@gmail.com", "https://drift.vercel.app",
				"https://drift.vercel.app/signup", "https://drift.vercel.app/reset-password",
				"https://drift.vercel.app"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Production mail requires MAIL_PASSWORD");
	}

	@Test
	void rejectsIncompleteStartTlsByTheMissingSetting() {
		assertThatThrownBy(() -> validExcept(false, true, true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Production mail requires MAIL_SMTP_AUTH");
		assertThatThrownBy(() -> validExcept(true, false, true))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Production mail requires MAIL_SMTP_STARTTLS_ENABLE");
		assertThatThrownBy(() -> validExcept(true, true, false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Production mail requires MAIL_SMTP_STARTTLS_REQUIRED");
	}

	@Test
	void rejectsALocalOrIncompleteFrontendUrl() {
		assertThatThrownBy(() -> withFrontend(" ", "https://drift.vercel.app/signup",
				"https://drift.vercel.app/reset-password", "https://drift.vercel.app"))
				.hasMessage("FRONTEND_BASE_URL must be an absolute hosted HTTPS URL without query or fragment");
		assertThatThrownBy(() -> withFrontend("http://drift.vercel.app", "https://drift.vercel.app/signup",
				"https://drift.vercel.app/reset-password", "https://drift.vercel.app"))
				.hasMessage("FRONTEND_BASE_URL must be an absolute hosted HTTPS URL without query or fragment");
		assertThatThrownBy(() -> withFrontend("https://drift.vercel.app?next=1", "https://drift.vercel.app/signup",
				"https://drift.vercel.app/reset-password", "https://drift.vercel.app"))
				.hasMessage("FRONTEND_BASE_URL must be an absolute hosted HTTPS URL without query or fragment");
		assertThatThrownBy(() -> withFrontend("https://user:pass@drift.vercel.app", "https://drift.vercel.app/signup",
				"https://drift.vercel.app/reset-password", "https://drift.vercel.app"))
				.hasMessage("FRONTEND_BASE_URL must be an absolute hosted HTTPS URL without query or fragment")
				.hasMessageNotContaining("pass");
		assertThatThrownBy(() -> withFrontend("https://drift.vercel.app#mail", "https://drift.vercel.app/signup",
				"https://drift.vercel.app/reset-password", "https://drift.vercel.app"))
				.hasMessage("FRONTEND_BASE_URL must be an absolute hosted HTTPS URL without query or fragment");
		assertThatThrownBy(() -> withFrom(" "))
				.hasMessage("Production mail requires a non-local MAIL_FROM_ADDRESS");
		assertThatThrownBy(() -> withFrom("noreply@localhost"))
				.hasMessage("Production mail requires a non-local MAIL_FROM_ADDRESS");
		assertThatThrownBy(() -> ProductionMailConfigurationGuard.validate("smtp.gmail.com", 465, "sender@gmail.com",
				"app-password", true, true, true, "sender@gmail.com", "https://drift.vercel.app",
				"https://drift.vercel.app/signup", "https://drift.vercel.app/reset-password",
				"https://drift.vercel.app"))
				.hasMessage("Production mail must use smtp.gmail.com on port 587");
	}

	private static void validExcept(boolean smtpAuth, boolean startTls, boolean startTlsRequired) {
		ProductionMailConfigurationGuard.validate("smtp.gmail.com", 587, "sender@gmail.com", "app-password",
				smtpAuth, startTls, startTlsRequired, "sender@gmail.com", "https://drift.vercel.app",
				"https://drift.vercel.app/signup", "https://drift.vercel.app/reset-password",
				"https://drift.vercel.app");
	}

	private static void withFrom(String from) {
		ProductionMailConfigurationGuard.validate("smtp.gmail.com", 587, "sender@gmail.com", "app-password",
				true, true, true, from, "https://drift.vercel.app", "https://drift.vercel.app/signup",
				"https://drift.vercel.app/reset-password", "https://drift.vercel.app");
	}

	private static void withFrontend(String frontend, String invitation, String reset, String risk) {
		ProductionMailConfigurationGuard.validate("smtp.gmail.com", 587, "sender@gmail.com", "app-password",
				true, true, true, "sender@gmail.com", frontend, invitation, reset, risk);
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
}
