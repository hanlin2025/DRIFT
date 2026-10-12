package com.drift.backend.account.profile;

import com.drift.backend.account.RiskNotificationLevel;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.authentication.CompanySummary;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Profile", description = "The signed-in user's own profile. Email, company and role are read-only.")
public record ProfileView(
		@Schema(example = "15") Long id,
		@Schema(example = "Alice Tan") String fullName,
		@Schema(example = "alice@example.com") String email,
		@Schema(example = "FREIGHT_FORWARDER") Role role,
		@Schema(description = "Company assigned to the user. Null when the user has none.", nullable = true)
		CompanySummary company,
		@Schema(description = "Whether email is wanted when a shipment risk reaches the minimum level. The choice is stored and no email is sent.", example = "false")
		boolean riskEmailNotificationsEnabled,
		@Schema(description = "Lowest shipment-risk level that should count. HIGH means HIGH and CRITICAL.", example = "HIGH")
		RiskNotificationLevel minimumRiskNotificationLevel) {

	static ProfileView of(UserAccount account) {
		return new ProfileView(account.getId(), account.getFullName(), account.getEmail(), account.getRole(),
				CompanySummary.of(account.getCompany()), account.isRiskEmailNotificationsEnabled(),
				account.getMinimumRiskNotificationLevel());
	}
}
