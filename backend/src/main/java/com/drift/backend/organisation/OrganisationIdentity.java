package com.drift.backend.organisation;

import java.util.Locale;
import java.util.regex.Pattern;

import com.drift.backend.organisation.exception.InvalidOrganisationException;

public record OrganisationIdentity(String code, String name) {

	public static final int NAME_MAX = 200;

	private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,49}");

	public static OrganisationIdentity parse(String code, String name) {
		String parsedCode = code == null ? "" : code.strip();
		String parsedName = name == null ? "" : name.strip();
		if (!CODE.matcher(parsedCode).matches()) {
			throw new InvalidOrganisationException(InvalidOrganisationException.CODE);
		}
		if (parsedName.isEmpty() || parsedName.length() > NAME_MAX || containsControl(parsedName)) {
			throw new InvalidOrganisationException(InvalidOrganisationException.NAME);
		}
		return new OrganisationIdentity(parsedCode.toUpperCase(Locale.ROOT), parsedName);
	}

	private static boolean containsControl(String value) {
		for (int index = 0; index < value.length(); index++) {
			if (Character.isISOControl(value.charAt(index))) {
				return true;
			}
		}
		return false;
	}
}
