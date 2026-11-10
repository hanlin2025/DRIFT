package com.drift.backend.email;

/** Shared link cleanup for invitation, password-reset, and risk-alert mail. */
public final class MailLinks {

	private MailLinks() {
	}

	/**
	 * Removes a trailing slash and a doubled slash that appears when a frontend origin
	 * already ends with {@code /} and a path is appended. The {@code ://} in the scheme is kept.
	 */
	public static String normalize(String url) {
		String trimmed = url == null ? "" : url.trim();
		int scheme = trimmed.indexOf("://");
		if (scheme < 0) {
			return collapse(trimmed);
		}
		return trimmed.substring(0, scheme + 3) + collapse(trimmed.substring(scheme + 3));
	}

	private static String collapse(String value) {
		String collapsed = value;
		while (collapsed.contains("//")) {
			collapsed = collapsed.replace("//", "/");
		}
		while (collapsed.endsWith("/")) {
			collapsed = collapsed.substring(0, collapsed.length() - 1);
		}
		return collapsed;
	}
}
