package com.drift.backend.account.setup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Compares the operator setup credential without placing it in a log or a response. */
public final class SetupCredential {

	public static final int MINIMUM_BYTES = 32;

	private SetupCredential() {
	}

	public static boolean isConfigured(String configured) {
		return configured != null && configured.getBytes(StandardCharsets.UTF_8).length >= MINIMUM_BYTES;
	}

	public static boolean matches(String configured, String presented) {
		if (!isConfigured(configured) || presented == null || presented.isBlank()) {
			return false;
		}
		return MessageDigest.isEqual(digest(configured), digest(presented));
	}

	private static byte[] digest(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("This runtime must support SHA-256", ex);
		}
	}
}
