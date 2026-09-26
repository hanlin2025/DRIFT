package com.drift.backend.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

	protected Company() { }

	public Long getId() { return id; }
	public String getName() { return name; }
	public boolean isActive() { return active; }
}
