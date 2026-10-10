package com.drift.backend.account.admin;

import java.time.Instant;

import com.drift.backend.account.Role;
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
@Table(name = "user_assignment_audits")
public class UserAssignmentAudit {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "operator_user_id", nullable = false)
	private UserAccount operator;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "target_user_id", nullable = false)
	private UserAccount target;

	@Enumerated(EnumType.STRING)
	@Column(name = "previous_role", nullable = false, length = 32)
	private Role previousRole;

	@Enumerated(EnumType.STRING)
	@Column(name = "assigned_role", nullable = false, length = 32)
	private Role assignedRole;

	@Column(name = "previous_organisation_id")
	private Long previousOrganisationId;

	@Column(name = "previous_organisation_code", length = 50)
	private String previousOrganisationCode;

	@Column(name = "previous_organisation_name", length = 200)
	private String previousOrganisationName;

	@Column(name = "organisation_id", nullable = false)
	private Long organisationId;

	@Column(name = "organisation_code", nullable = false, length = 50)
	private String organisationCode;

	@Column(name = "organisation_name", nullable = false, length = 200)
	private String organisationName;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	protected UserAssignmentAudit() {
	}

	public UserAssignmentAudit(UserAccount operator, UserAccount target, Role previousRole, Company previousOrganisation,
			Company organisation, Instant recordedAt) {
		this.operator = operator;
		this.target = target;
		this.previousRole = previousRole;
		this.assignedRole = target.getRole();
		if (previousOrganisation != null) {
			this.previousOrganisationId = previousOrganisation.getId();
			this.previousOrganisationCode = previousOrganisation.getCode();
			this.previousOrganisationName = previousOrganisation.getName();
		}
		this.organisationId = organisation.getId();
		this.organisationCode = organisation.getCode();
		this.organisationName = organisation.getName();
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

	public Role getPreviousRole() {
		return previousRole;
	}

	public Role getAssignedRole() {
		return assignedRole;
	}

	public Long getPreviousOrganisationId() {
		return previousOrganisationId;
	}

	public String getPreviousOrganisationCode() {
		return previousOrganisationCode;
	}

	public String getPreviousOrganisationName() {
		return previousOrganisationName;
	}

	public Long getOrganisationId() {
		return organisationId;
	}

	public String getOrganisationCode() {
		return organisationCode;
	}

	public String getOrganisationName() {
		return organisationName;
	}

	public Instant getRecordedAt() {
		return recordedAt;
	}
}
