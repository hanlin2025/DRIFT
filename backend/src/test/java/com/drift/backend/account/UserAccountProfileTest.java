package com.drift.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.drift.backend.account.exception.InvalidProfileException;
import com.drift.backend.shipment.ShipmentRisk;

class UserAccountProfileTest {

	@Test
	void newAccountKeepsIdentityAndStoresNotificationsOffAtHigh() {
		UserAccount account = account();

		assertThat(account.getFullName()).isEqualTo("Alice Tan");
		assertThat(account.getEmail()).isEqualTo("alice@example.com");
		assertThat(account.getRole()).isEqualTo(Role.FREIGHT_FORWARDER);
		assertThat(account.getCompany()).isNull();
		assertThat(account.getSessionGeneration()).isZero();
		assertThat(account.isRiskEmailNotificationsEnabled()).isFalse();
		assertThat(account.getMinimumRiskNotificationLevel()).isEqualTo(RiskNotificationLevel.HIGH);
		assertThat(account.notifiesFor(RiskNotificationLevel.CRITICAL)).isFalse();
	}

	@Test
	void renameTrimsTheNameAndLeavesTheSessionAndPasswordAlone() {
		UserAccount account = account();
		String hash = account.getPasswordHash();

		account.rename("  Amina Rahman  ");

		assertThat(account.getFullName()).isEqualTo("Amina Rahman");
		assertThat(account.getEmail()).isEqualTo("alice@example.com");
		assertThat(account.getPasswordHash()).isEqualTo(hash);
		assertThat(account.getSessionGeneration()).isZero();
		assertThat(account.getRole()).isEqualTo(Role.FREIGHT_FORWARDER);
	}

	@Test
	void renameRejectsAnEmptyOrOverlongName() {
		UserAccount account = account();

		assertThatThrownBy(() -> account.rename("   ")).isInstanceOf(InvalidProfileException.class)
				.hasMessage(InvalidProfileException.NAME_REQUIRED);
		assertThatThrownBy(() -> account.rename("n".repeat(201))).isInstanceOf(InvalidProfileException.class)
				.hasMessage(InvalidProfileException.NAME_TOO_LONG);
		assertThat(account.getFullName()).isEqualTo("Alice Tan");
		account.rename("n".repeat(200));
		assertThat(account.getFullName()).hasSize(200);
	}

	@Test
	void replacingThePasswordHashUsesTheSameColumnAndEndsOtherSessions() {
		UserAccount account = account();

		account.replacePasswordHash("new-hash");

		assertThat(account.getPasswordHash()).isEqualTo("new-hash");
		assertThat(account.getSessionGeneration()).isEqualTo(1);
		assertThat(account.getEmail()).isEqualTo("alice@example.com");
		assertThatThrownBy(() -> account.replacePasswordHash("  ")).isInstanceOf(IllegalArgumentException.class);
		assertThat(account.getPasswordHash()).isEqualTo("new-hash");
	}

	@Test
	void notificationChoiceUsesTheShipmentRiskLevelsAndDoesNotSendMail() {
		assertThat(RiskNotificationLevel.values()).extracting(Enum::name)
				.containsExactly(java.util.Arrays.stream(ShipmentRisk.Level.values()).map(Enum::name).toArray(String[]::new));

		RiskNotificationLevel high = RiskNotificationLevel.HIGH;
		assertThat(high.includes(RiskNotificationLevel.LOW)).isFalse();
		assertThat(high.includes(RiskNotificationLevel.MEDIUM)).isFalse();
		assertThat(high.includes(RiskNotificationLevel.HIGH)).isTrue();
		assertThat(high.includes(RiskNotificationLevel.CRITICAL)).isTrue();
		assertThat(high.includes(null)).isFalse();

		UserAccount account = account();
		account.chooseRiskNotifications(true, RiskNotificationLevel.HIGH);
		assertThat(account.notifiesFor(RiskNotificationLevel.MEDIUM)).isFalse();
		assertThat(account.notifiesFor(RiskNotificationLevel.HIGH)).isTrue();
		assertThat(account.notifiesFor(RiskNotificationLevel.CRITICAL)).isTrue();
		assertThat(account.getSessionGeneration()).isZero();
		assertThat(account.getPasswordHash()).isEqualTo("hash");

		account.chooseRiskNotifications(false, RiskNotificationLevel.LOW);
		assertThat(account.notifiesFor(RiskNotificationLevel.CRITICAL)).isFalse();
		assertThatThrownBy(() -> account.chooseRiskNotifications(true, null))
				.isInstanceOf(InvalidProfileException.class)
				.hasMessage(InvalidProfileException.RISK_LEVEL);
	}

	private static UserAccount account() {
		return new UserAccount("Alice Tan", "alice@example.com", "hash", new AccountRole(Role.FREIGHT_FORWARDER), null);
	}
}
