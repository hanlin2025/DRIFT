package com.drift.backend.account.signup;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Records verification emails instead of sending them; can be told to fail like a broken mail transport. */
final class CapturingEmailVerificationMailer implements EmailVerificationMailer {

	record Sent(String email, String link, Duration validFor) {
	}

	private final List<Sent> sent = new ArrayList<>();
	private volatile boolean fail;

	@Override
	public synchronized void sendVerificationLink(String email, String verificationLink, Duration validFor) {
		if (fail) {
			throw new IllegalStateException("mail transport failed");
		}
		sent.add(new Sent(email, verificationLink, validFor));
	}

	synchronized List<Sent> sentTo(String email) {
		return sent.stream().filter(item -> item.email().equals(email)).toList();
	}

	void failDelivery(boolean fail) {
		this.fail = fail;
	}

	synchronized void reset() {
		sent.clear();
		fail = false;
	}

	static String tokenFrom(String link) {
		String marker = "#token=";
		int start = link.indexOf(marker);
		if (start < 0) {
			throw new AssertionError("No token in " + link.substring(0, Math.min(link.length(), 40)));
		}
		return link.substring(start + marker.length());
	}
}
