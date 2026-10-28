package com.drift.backend.account;

import java.time.Instant;

import com.drift.backend.account.exception.InvalidProfileException;
import com.drift.backend.company.Company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "users")
public class UserAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "full_name", nullable = false, length = 200)
	private String fullName;

	@Column(nullable = false, length = 320, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "role_id", nullable = false)
	private AccountRole role;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "company_id")
	private Company company;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "session_generation", nullable = false)
	private long sessionGeneration;

	@Column(name = "risk_email_notifications_enabled", nullable = false)
	private boolean riskEmailNotificationsEnabled;

	@Enumerated(EnumType.STRING)
	@Column(name = "minimum_risk_notification_level", nullable = false, length = 16)
	private RiskNotificationLevel minimumRiskNotificationLevel = RiskNotificationLevel.HIGH;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private AccountStatus status = AccountStatus.ACTIVE;

	@Version
	@Column(nullable = false)
	private Long version;

	protected UserAccount() {
	}

	public UserAccount(String fullName, String email, String passwordHash, AccountRole role, Company company) {
		this.fullName = fullName;
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = role;
		this.company = company;
		this.createdAt = Instant.now();
		this.sessionGeneration = 0;
		this.riskEmailNotificationsEnabled = false;
		this.minimumRiskNotificationLevel = RiskNotificationLevel.HIGH;
		this.status = AccountStatus.ACTIVE;
	}

	public Long getId() {
		return id;
	}

	public String getFullName() {
		return fullName;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Role getRole() {
		return role.getCode();
	}

	public Company getCompany() {
		return company;
	}

	public long getSessionGeneration() {
		return sessionGeneration;
	}

	public boolean isRiskEmailNotificationsEnabled() {
		return riskEmailNotificationsEnabled;
	}

	public RiskNotificationLevel getMinimumRiskNotificationLevel() {
		return minimumRiskNotificationLevel;
	}

	public AccountStatus getStatus() {
		return status;
	}

	public void rename(String fullName) {
		if (fullName == null || fullName.isBlank()) {
			throw new InvalidProfileException(InvalidProfileException.NAME_REQUIRED);
		}
		String trimmed = fullName.strip();
		if (trimmed.length() > 200) {
			throw new InvalidProfileException(InvalidProfileException.NAME_TOO_LONG);
		}
		this.fullName = trimmed;
	}

	public void replacePasswordHash(String passwordHash) {
		if (passwordHash == null || passwordHash.isBlank()) {
			throw new IllegalArgumentException("Password hash is required");
		}
		this.passwordHash = passwordHash;
		sessionGeneration++;
	}

	public void chooseRiskNotifications(boolean enabled, RiskNotificationLevel minimum) {
		if (minimum == null) {
			throw new InvalidProfileException(InvalidProfileException.RISK_LEVEL);
		}
		this.riskEmailNotificationsEnabled = enabled;
		this.minimumRiskNotificationLevel = minimum;
	}

	public boolean notifiesFor(RiskNotificationLevel actual) {
		return riskEmailNotificationsEnabled && minimumRiskNotificationLevel.includes(actual);
	}

	public boolean assign(AccountRole nextRole, Company nextCompany) {
		boolean sameRole = role.getCode() == nextRole.getCode();
		boolean sameCompany = company != null && company.getId().equals(nextCompany.getId());
		this.role = nextRole;
		this.company = nextCompany;
		if (sameRole && sameCompany) {
			return false;
		}
		sessionGeneration++;
		return true;
	}

	public void remove() {
		if (status == AccountStatus.REMOVED) {
			throw new IllegalStateException("Account is already removed");
		}
		this.status = AccountStatus.REMOVED;
		sessionGeneration++;
	}
}
