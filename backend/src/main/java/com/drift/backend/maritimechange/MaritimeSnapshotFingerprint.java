package com.drift.backend.maritimechange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class MaritimeSnapshotFingerprint {

	private MaritimeSnapshotFingerprint() {
	}

	static String of(String sourceType, String sourceIdentity, String vesselImo, String location, String previousValue,
			String currentValue, String sourceEventAt, String effectiveFrom, String effectiveUntil) {
		String canonical = String.join("\n", text(sourceType), text(sourceIdentity), text(vesselImo), text(location),
				text(previousValue), text(currentValue), text(sourceEventAt), text(effectiveFrom), text(effectiveUntil));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required to fingerprint a maritime snapshot", exception);
		}
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
