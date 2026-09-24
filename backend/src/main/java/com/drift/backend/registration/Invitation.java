package com.drift.backend.registration;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "invitations")
public class Invitation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 320)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private InvitationStatus status;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	protected Invitation() {
	}

	public Role getRole() {
		return role;
	}

	public void consume() {
		this.status = InvitationStatus.CONSUMED;
		this.consumedAt = Instant.now();
	}
}
