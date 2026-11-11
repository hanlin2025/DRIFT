package com.drift.backend.shipment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/** Separate opt-in and fixed address allowlist for controlled synthetic scenario mail. */
@ConfigurationProperties(prefix = "app.controlled-scenario.email")
public class ControlledScenarioEmailProperties {
	private boolean enabled;
	private String recipients = "";

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getRecipients() { return recipients; }
	public void setRecipients(String recipients) { this.recipients = recipients == null ? "" : recipients; }

	/** Configured operator-owned recipients only. Request data never enters this allowlist. */
	public List<String> approvedRecipients() {
		LinkedHashMap<String, String> unique = new LinkedHashMap<>();
		for (String raw : recipients.split(",")) {
			String candidate = raw.strip();
			if (candidate.isEmpty() || !isValidAddress(candidate)) continue;
			unique.putIfAbsent(candidate.toLowerCase(Locale.ROOT), candidate);
		}
		return List.copyOf(new ArrayList<>(unique.values()));
	}

	private static boolean isValidAddress(String candidate) {
		try {
			InternetAddress address = new InternetAddress(candidate, true);
			address.validate();
			return address.getPersonal() == null && candidate.equals(address.getAddress());
		} catch (AddressException exception) {
			return false;
		}
	}
}
