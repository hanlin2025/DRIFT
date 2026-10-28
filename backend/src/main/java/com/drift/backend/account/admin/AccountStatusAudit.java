package com.drift.backend.account.admin;

import java.time.Instant;

import com.drift.backend.account.AccountStatus;
import com.drift.backend.account.UserAccount;
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

@Entity
@Table(name = "account_status_audits")
public class AccountStatusAudit {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "operator_user_id", nullable = false)
	private UserAccount operator;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "target_user_id", nullable = false)
	private UserAccount target;

	@Column(name = "organisation_id", nullable = false)
	private Long organisationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "previous_status", nullable = false, length = 16)
	private AccountStatus previousStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "new_status", nullable = false, length = 16)
	private AccountStatus newStatus;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	protected AccountStatusAudit() {
	}

	public AccountStatusAudit(UserAccount operator, UserAccount target, Company organisation,
			AccountStatus previousStatus, AccountStatus newStatus, Instant recordedAt) {
		this.operator = operator;
		this.target = target;
		this.organisationId = organisation.getId();
		this.previousStatus = previousStatus;
		this.newStatus = newStatus;
		this.recordedAt = recordedAt;
	}

	public Long getId() {
		return id;
	}

	public UserAccount getOperator() {
		return operator;
	}

	public UserAccount getTarget() {
		return target;
	}

	public Long getOrganisationId() {
		return organisationId;
	}

	public AccountStatus getPreviousStatus() {
		return previousStatus;
	}

	public AccountStatus getNewStatus() {
		return newStatus;
	}

	public Instant getRecordedAt() {
		return recordedAt;
	}
}
