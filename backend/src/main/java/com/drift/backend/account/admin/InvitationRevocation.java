package com.drift.backend.account.admin;

import java.time.Instant;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.registration.invitation.Invitation;
import com.drift.backend.company.Company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "invitation_revocations")
public class InvitationRevocation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "invitation_id", nullable = false, unique = true)
	private Invitation invitation;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "operator_user_id", nullable = false)
	private UserAccount operator;

	@Column(name = "organisation_id", nullable = false)
	private Long organisationId;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	protected InvitationRevocation() {
	}

	public InvitationRevocation(Invitation invitation, UserAccount operator, Company organisation, Instant recordedAt) {
		this.invitation = invitation;
		this.operator = operator;
		this.organisationId = organisation.getId();
		this.recordedAt = recordedAt;
	}

	public Long getId() {
		return id;
	}

	public Invitation getInvitation() {
		return invitation;
	}

	public UserAccount getOperator() {
		return operator;
	}

	public Long getOrganisationId() {
		return organisationId;
	}

	public Instant getRecordedAt() {
		return recordedAt;
	}
}
