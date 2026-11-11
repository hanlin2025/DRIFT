package com.drift.backend.account.signup;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Committed-data helpers for signup tests, which run outside a test transaction to observe real rollbacks. */
final class SignupFixtures {
	private static final Set<String> SEEDED_COMPANY_CODES = Set.of("HARBOURLINE_DEMO", "STRAITS_FRESH_DEMO");

	private SignupFixtures() {
	}

	static String uniqueCode(String prefix) {
		return (prefix + UUID.randomUUID().toString().replace("-", "")).substring(0, 30).toUpperCase();
	}

	static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID() + "@example.com";
	}

	static String body(String code, String name, String fullName, String email, String password) {
		return """
				{"companyCode":%s,"companyName":%s,"fullName":%s,"email":%s,"password":%s}
				""".formatted(json(code), json(name), json(fullName), json(email), json(password));
	}

	private static String json(String value) {
		if (value == null) {
			return "null";
		}
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\u0001", "\\u0001") + "\"";
	}

	static void cleanup(JdbcTemplate jdbc, Collection<String> emails, Collection<String> codes) {
		for (String code : codes) {
			if (isSeededCompany(code)) {
				continue;
			}
			jdbc.update("UPDATE companies SET created_by_user_id = NULL WHERE code = ?", code);
		}
		for (String email : emails) {
			jdbc.update("DELETE FROM company_signup_audits WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
			jdbc.update("DELETE FROM email_verification_tokens WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
			jdbc.update("UPDATE companies SET created_by_user_id = NULL WHERE created_by_user_id IN (SELECT id FROM users WHERE email = ?)", email);
			jdbc.update("DELETE FROM users WHERE email = ?", email);
		}
		for (String code : codes) {
			if (isSeededCompany(code)) {
				continue;
			}
			jdbc.update("DELETE FROM company_signup_audits WHERE company_id IN (SELECT id FROM companies WHERE code = ?)", code);
			jdbc.update("DELETE FROM companies WHERE code = ? AND NOT EXISTS (SELECT 1 FROM users WHERE company_id = companies.id)", code);
		}
	}

	private static boolean isSeededCompany(String code) {
		return code != null && SEEDED_COMPANY_CODES.contains(code.trim().toUpperCase(Locale.ROOT));
	}
}
