package com.drift.backend.registration.invitation;

import java.time.Instant;
import com.drift.backend.company.Company;
import com.drift.backend.registration.account.Role;
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
@Table(name = "invitations")
public class Invitation {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 320)
	private String email;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "company_id", nullable = false)
	private Company company;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private Role role;
	@Column(name = "token_hash", nullable = false, unique = true, length = 64)
	private String tokenHash;
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private InvitationStatus status;
	@Column(name = "consumed_at")
	private Instant consumedAt;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Invitation() { }

	public boolean isEligible(Instant now) {
		return status == InvitationStatus.PENDING && expiresAt.isAfter(now) && company.isActive();
	}

	public void consume(Instant now) {
		status = InvitationStatus.CONSUMED;
		consumedAt = now;
	}

	public String getEmail() { return email; }
	public Company getCompany() { return company; }
	public Role getRole() { return role; }
	public Instant getExpiresAt() { return expiresAt; }
}
