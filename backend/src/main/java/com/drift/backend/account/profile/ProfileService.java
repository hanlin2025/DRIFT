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

@Service
public class ProfileService {

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;

	public ProfileService(UserAccountRepository users, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional(readOnly = true)
	public ProfileView view(AuthenticatedUser principal) {
		return ProfileView.of(account(principal));
	}

	@Transactional
	public ProfileView update(AuthenticatedUser principal, UpdateProfileRequest request) {
		if (request == null) {
			throw new InvalidProfileException(InvalidProfileException.MISSING);
		}
		UserAccount account = account(principal);
		if (request.userId() != null && !request.userId().equals(account.getId())) {
			throw new ProfileForbiddenException();
		}
		rejectLockedFields(request);
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
		if (changePassword && !blank(request.currentPassword()) && !blank(request.newPassword())) {
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

	private static void rejectLockedFields(UpdateProfileRequest request) {
		if (request.email() != null || request.company() != null || request.companyId() != null
				|| request.organisation() != null || request.organisationId() != null
				|| request.role() != null || request.roleId() != null
				|| request.photo() != null || request.profilePhoto() != null) {
			throw new InvalidProfileException(InvalidProfileException.LOCKED);
		}
		if (request.password() != null) {
			throw new InvalidProfileException(InvalidProfileException.PASSWORD_FIELDS);
		}
	}

	private void checkPassword(UserAccount account, String currentPassword, String newPassword) {
		if (blank(currentPassword) && blank(newPassword)) {
			return;
		}
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
		if (raw.isBlank()) {
			throw new InvalidProfileException(InvalidProfileException.RISK_LEVEL);
		}
		try {
			return RiskNotificationLevel.valueOf(raw.strip());
		} catch (IllegalArgumentException ex) {
			throw new InvalidProfileException(InvalidProfileException.RISK_LEVEL);
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
