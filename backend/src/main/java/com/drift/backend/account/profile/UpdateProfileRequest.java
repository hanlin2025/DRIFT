package com.drift.backend.account.profile;

public record UpdateProfileRequest(
		Long userId,
		String fullName,
		Object email,
		Object company,
		Long companyId,
		Object organisation,
		Long organisationId,
		Object role,
		Long roleId,
		Object photo,
		Object profilePhoto,
		Object password,
		String currentPassword,
		String newPassword,
		Boolean riskEmailNotificationsEnabled,
		String minimumRiskNotificationLevel) {

	@Override
	public String toString() {
		return "UpdateProfileRequest[redacted]";
	}
}
