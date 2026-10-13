package com.drift.backend.account.profile;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.RiskNotificationLevel;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.InvalidProfileException;
import com.drift.backend.account.exception.InvalidRegistrationException;
import com.drift.backend.account.exception.ProfileForbiddenException;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.account.registration.PasswordPolicy;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProfileService {

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final ObjectMapper objectMapper;

	public ProfileService(UserAccountRepository users, PasswordEncoder passwordEncoder, ObjectMapper objectMapper) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.objectMapper = objectMapper;
	}

	@Transactional(readOnly = true)
	public ProfileView view(AuthenticatedUser principal) {
		return ProfileView.of(account(principal));
	}

	@Transactional
	public ProfileView update(AuthenticatedUser principal, JsonNode body) {
		UpdateProfileRequest request = request(body);
		UserAccount account = account(principal);
		if (request.userId() != null && !request.userId().equals(account.getId())) {
			throw new ProfileForbiddenException();
		}
		RiskNotificationLevel nextLevel = request.minimumRiskNotificationLevel() == null
				? null
				: level(request.minimumRiskNotificationLevel());
		boolean changePassword = request.currentPassword() != null || request.newPassword() != null;
		if (changePassword) {
			checkPassword(account, request.currentPassword(), request.newPassword());
		}
		if (request.fullName() != null) {
			account.rename(request.fullName());
		}
		if (changePassword) {
			account.replacePasswordHash(passwordEncoder.encode(request.newPassword()));
		}
		if (request.riskEmailNotificationsEnabled() != null || nextLevel != null) {
			boolean enabled = request.riskEmailNotificationsEnabled() == null
					? account.isRiskEmailNotificationsEnabled()
					: request.riskEmailNotificationsEnabled();
			RiskNotificationLevel minimum = nextLevel == null ? account.getMinimumRiskNotificationLevel() : nextLevel;
			account.chooseRiskNotifications(enabled, minimum);
		}
		users.saveAndFlush(account);
		return ProfileView.of(account);
	}

	private UserAccount account(AuthenticatedUser principal) {
		return users.findForProfile(principal.id()).orElseThrow(SessionEndedException::new);
	}

	private UpdateProfileRequest request(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new InvalidProfileException(InvalidProfileException.MISSING);
		}
		if (body.has("email") || body.has("company") || body.has("companyId")
				|| body.has("organisation") || body.has("organisationId")
				|| body.has("role") || body.has("roleId")
				|| body.has("photo") || body.has("profilePhoto")) {
			throw new InvalidProfileException(InvalidProfileException.LOCKED);
		}
		if (body.has("password")) {
			throw new InvalidProfileException(InvalidProfileException.PASSWORD_FIELDS);
		}
		try {
			return objectMapper.convertValue(body, UpdateProfileRequest.class);
		} catch (IllegalArgumentException | JacksonException ex) {
			throw new InvalidProfileException(InvalidProfileException.MISSING);
		}
	}

	private void checkPassword(UserAccount account, String currentPassword, String newPassword) {
		if (blank(currentPassword)) {
			throw new InvalidProfileException(InvalidProfileException.CURRENT_REQUIRED);
		}
		if (blank(newPassword)) {
			throw new InvalidProfileException(InvalidProfileException.NEW_REQUIRED);
		}
		if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
			throw new InvalidProfileException(InvalidProfileException.CURRENT_INCORRECT);
		}
		try {
			PasswordPolicy.check(newPassword);
		} catch (InvalidRegistrationException ex) {
			throw new InvalidProfileException(ex.getMessage());
		}
	}

	private static RiskNotificationLevel level(String raw) {
		try {
			return RiskNotificationLevel.valueOf(raw);
		} catch (IllegalArgumentException | NullPointerException ex) {
			throw new InvalidProfileException(InvalidProfileException.RISK_LEVEL);
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
