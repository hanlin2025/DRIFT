package com.drift.backend.account.signup;

import java.time.Instant;

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
@Table(name = "company_signup_audits")
public class CompanySignupAudit {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private UserAccount user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "company_id", nullable = false)
	private Company company;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private SignupEvent event;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	protected CompanySignupAudit() {
	}

	public CompanySignupAudit(UserAccount user, Company company, SignupEvent event, Instant recordedAt) {
		this.user = user;
		this.company = company;
		this.event = event;
		this.recordedAt = recordedAt;
	}

	public Long getId() {
		return id;
	}

	public SignupEvent getEvent() {
		return event;
	}
}
