package com.drift.backend.company;

import java.time.Instant;

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
@Table(name = "forwarder_importer_relationships")
public class ForwarderImporterRelationship {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "forwarder_company_id", nullable = false)
	private Company forwarderCompany;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "importer_company_id", nullable = false)
	private Company importerCompany;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by_user_id", nullable = false)
	private UserAccount createdBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "updated_by_user_id", nullable = false)
	private UserAccount updatedBy;

	protected ForwarderImporterRelationship() {
	}

	public ForwarderImporterRelationship(Company forwarderCompany, Company importerCompany, UserAccount actor) {
		if (forwarderCompany.getId().equals(importerCompany.getId())) {
			throw new IllegalArgumentException("A forwarder and importer must be different companies");
		}
		this.forwarderCompany = forwarderCompany;
		this.importerCompany = importerCompany;
		this.active = true;
		this.createdAt = Instant.now();
		this.createdBy = actor;
		this.updatedAt = this.createdAt;
		this.updatedBy = actor;
	}

	public Long getId() {
		return id;
	}

	public boolean isActive() {
		return active;
	}

	public void deactivate(UserAccount actor) {
		active = false;
		updatedAt = Instant.now();
		updatedBy = actor;
	}
}
