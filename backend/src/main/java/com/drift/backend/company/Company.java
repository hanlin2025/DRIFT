package com.drift.backend.company;

import com.drift.backend.account.UserAccount;

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
@Table(name = "companies")
public class Company {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, unique = true, length = 50)
	private String code;
	@Column(nullable = false, length = 200)
	private String name;
	@Column(nullable = false)
	private boolean active;
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_user_id")
	private UserAccount createdBy;

	protected Company() { }

	public static Company createActive(String code, String name, UserAccount createdBy) {
		Company company = new Company();
		company.code = code;
		company.name = name;
		company.active = true;
		company.createdBy = createdBy;
		return company;
	}

	/** A self-service company stays inactive until its first administrator verifies their email. */
	public static Company createInactive(String code, String name) {
		Company company = new Company();
		company.code = code;
		company.name = name;
		company.active = false;
		return company;
	}

	public void assignCreator(UserAccount creator) {
		this.createdBy = creator;
	}

	/** A pending self-service signup may change its code and name until the company is activated. */
	public void replacePendingIdentity(String code, String name) {
		if (active) {
			throw new IllegalStateException("Only an inactive company can change its identity");
		}
		this.code = code;
		this.name = name;
	}

	public void activate() {
		this.active = true;
	}

	public Long getId() { return id; }
	public String getCode() { return code; }
	public String getName() { return name; }
	public boolean isActive() { return active; }
	public UserAccount getCreatedBy() { return createdBy; }
}
