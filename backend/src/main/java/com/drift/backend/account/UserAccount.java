package com.drift.backend.account;

import java.time.Instant;

import com.drift.backend.company.Company;

import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
}
