package com.drift.backend.email;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Fails startup when production mail configuration is missing or points at local development services. */
@Configuration
@Profile("production")
public class ProductionMailConfigurationGuard {

	public ProductionMailConfigurationGuard(
			@Value("${spring.mail.host:}") String host,
			@Value("${spring.mail.port:0}") int port,
			@Value("${spring.mail.username:}") String username,
			@Value("${spring.mail.password:}") String password,
			@Value("${spring.mail.properties.mail.smtp.auth:false}") boolean smtpAuth,
			@Value("${spring.mail.properties.mail.smtp.starttls.enable:false}") boolean startTls,
			@Value("${spring.mail.properties.mail.smtp.starttls.required:false}") boolean startTlsRequired,
			@Value("${app.mail.from:}") String from,
			@Value("${app.frontend-base-url:}") String frontendBaseUrl,
			@Value("${app.invitation.link-base-url:}") String invitationLinkBaseUrl,
			@Value("${app.password-reset.link-base-url:}") String passwordResetLinkBaseUrl,
			@Value("${app.risk-alerts.mail.frontend-base-url:}") String riskAlertFrontendBaseUrl) {
		validate(host, port, username, password, smtpAuth, startTls, startTlsRequired, from, frontendBaseUrl,
				invitationLinkBaseUrl, passwordResetLinkBaseUrl, riskAlertFrontendBaseUrl);
	}

	static void validate(String host, int port, String username, String password, boolean smtpAuth, boolean startTls,
			boolean startTlsRequired, String from, String frontendBaseUrl, String invitationLinkBaseUrl,
			String passwordResetLinkBaseUrl, String riskAlertFrontendBaseUrl) {
		if (!"smtp.gmail.com".equalsIgnoreCase(value(host)) || port != 587) {
			throw new IllegalStateException("Production mail must use smtp.gmail.com on port 587");
		}
		if (!smtpAuth || !startTls || !startTlsRequired) {
			throw new IllegalStateException("Production mail requires SMTP authentication and required STARTTLS");
		}
		if (blank(username) || blank(password)) {
			throw new IllegalStateException("Production mail requires MAIL_USERNAME and MAIL_PASSWORD");
		}
		if (blank(from) || value(from).toLowerCase(Locale.ROOT).endsWith("@localhost")) {
			throw new IllegalStateException("Production mail requires a non-local MAIL_FROM_ADDRESS");
		}

		URI frontend = hostedHttpsUrl(frontendBaseUrl, "FRONTEND_BASE_URL");
		for (var link : new String[][] {
				{ "INVITATION_LINK_BASE_URL", invitationLinkBaseUrl },
				{ "PASSWORD_RESET_LINK_BASE_URL", passwordResetLinkBaseUrl },
				{ "RISK_ALERTS_FRONTEND_BASE_URL", riskAlertFrontendBaseUrl }
		}) {
			URI url = hostedHttpsUrl(link[1], link[0]);
			if (!frontend.getHost().equalsIgnoreCase(url.getHost())) {
				throw new IllegalStateException(link[0] + " must use the FRONTEND_BASE_URL host in production");
			}
		}
	}

	private static URI hostedHttpsUrl(String value, String propertyName) {
		try {
			URI uri = new URI(value(value));
			String host = uri.getHost();
			if (!"https".equalsIgnoreCase(uri.getScheme()) || blank(host) || isLocalHost(host)
					|| uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
				throw new IllegalStateException(propertyName + " must be an absolute hosted HTTPS URL without query or fragment");
			}
			return uri;
		} catch (URISyntaxException ex) {
			throw new IllegalStateException(propertyName + " must be an absolute hosted HTTPS URL", ex);
		}
	}

	private static boolean isLocalHost(String host) {
		String normalized = host.toLowerCase(Locale.ROOT);
		return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1")
				|| normalized.endsWith(".localhost");
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String value(String value) {
		return value == null ? "" : value.trim();
	}
}
