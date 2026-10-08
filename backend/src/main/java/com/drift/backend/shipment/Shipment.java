package com.drift.backend.shipment;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.drift.backend.account.UserAccount;
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
@Table(name = "shipments")
public class Shipment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "company_id", nullable = false)
	private Company company;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by_user_id", nullable = false)
	private UserAccount createdBy;

	@Column(name = "shipment_reference", nullable = false, length = 100)
	private String shipmentReference;

	@Column(nullable = false, length = 200)
	private String origin;

	@Column(nullable = false, length = 200)
	private String destination;

	@Column(name = "transshipment_port", nullable = false, length = 200)
	private String transshipmentPort;

	@Column(name = "mother_vessel", nullable = false, length = 200)
	private String motherVessel;

	@Column(name = "planned_mother_arrival_at", nullable = false)
	private OffsetDateTime plannedMotherArrivalAt;

	@Column(name = "feeder_vessel", nullable = false, length = 200)
	private String feederVessel;

	@Column(name = "planned_feeder_departure_at", nullable = false)
	private OffsetDateTime plannedFeederDepartureAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public static final String ACTIVE = "ACTIVE";
	public static final String ARCHIVED = "ARCHIVED";

	@Column(nullable = false, length = 16)
	private String status;

	protected Shipment() {
	}

	public Shipment(Company company, UserAccount createdBy, String shipmentReference, String origin,
			String destination, String transshipmentPort, String motherVessel, OffsetDateTime plannedMotherArrivalAt,
			String feederVessel, OffsetDateTime plannedFeederDepartureAt) {
		this.company = company;
		this.createdBy = createdBy;
		this.shipmentReference = shipmentReference;
		this.origin = origin;
		this.destination = destination;
		this.transshipmentPort = transshipmentPort;
		this.motherVessel = motherVessel;
		this.plannedMotherArrivalAt = plannedMotherArrivalAt;
		this.feederVessel = feederVessel;
		this.plannedFeederDepartureAt = plannedFeederDepartureAt;
		this.createdAt = Instant.now();
		this.status = ACTIVE;
	}

	public void archive() {
		this.status = ARCHIVED;
	}

	public Long getId() { return id; }
	public String getShipmentReference() { return shipmentReference; }
	public String getOrigin() { return origin; }
	public String getDestination() { return destination; }
	public String getTransshipmentPort() { return transshipmentPort; }
	public String getMotherVessel() { return motherVessel; }
	public OffsetDateTime getPlannedMotherArrivalAt() { return plannedMotherArrivalAt; }
	public String getFeederVessel() { return feederVessel; }
	public OffsetDateTime getPlannedFeederDepartureAt() { return plannedFeederDepartureAt; }
	public Instant getCreatedAt() { return createdAt; }
	public String getStatus() { return status; }
}
