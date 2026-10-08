package com.drift.backend.account.passwordreset;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class ResetTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private ResetTokens() {
	}

	public static String generate() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("This runtime must support SHA-256", ex);
		}
	}

	public static String link(String baseUrl, String token) {
		String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		return base + "#token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
	}
}
