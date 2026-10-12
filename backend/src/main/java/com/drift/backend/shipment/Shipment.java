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
import jakarta.persistence.Version;

@Entity
@Table(name = "shipments")
public class Shipment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "company_id", nullable = false)
	private Company company;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "importer_company_id")
	private Company importerCompany;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by_user_id", nullable = false)
	private UserAccount createdBy;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "updated_by_user_id", nullable = false)
	private UserAccount updatedBy;

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

	@Column(name = "mother_vessel_imo", length = 7)
	private String motherVesselImo;

	@Column(name = "mother_vessel_mmsi", length = 9)
	private String motherVesselMmsi;

	@Column(name = "mother_vessel_operator", length = 200)
	private String motherVesselOperator;

	@Column(name = "feeder_vessel_imo", length = 7)
	private String feederVesselImo;

	@Column(name = "feeder_vessel_mmsi", length = 9)
	private String feederVesselMmsi;

	@Column(name = "feeder_vessel_operator", length = 200)
	private String feederVesselOperator;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(nullable = false)
	private Long version;

	protected Shipment() {
	}

	public Shipment(Company company, UserAccount createdBy, String shipmentReference, String origin,
			String destination, String transshipmentPort, String motherVessel, OffsetDateTime plannedMotherArrivalAt,
			String feederVessel, OffsetDateTime plannedFeederDepartureAt) {
		this.company = company;
		this.createdBy = createdBy;
		this.updatedBy = createdBy;
		this.shipmentReference = shipmentReference;
		this.origin = origin;
		this.destination = destination;
		this.transshipmentPort = transshipmentPort;
		this.motherVessel = motherVessel;
		this.plannedMotherArrivalAt = plannedMotherArrivalAt;
		this.feederVessel = feederVessel;
		this.plannedFeederDepartureAt = plannedFeederDepartureAt;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	void markUpdated(UserAccount updatedBy, Instant updatedAt) {
		this.updatedBy = updatedBy;
		this.updatedAt = updatedAt;
	}

	void replaceDetails(ShipmentDetails details, UserAccount updatedBy, Instant updatedAt) {
		this.shipmentReference = details.shipmentReference();
		this.origin = details.origin();
		this.destination = details.destination();
		this.transshipmentPort = details.transshipmentPort();
		this.motherVessel = details.motherVessel();
		this.plannedMotherArrivalAt = details.plannedMotherArrivalAt();
		this.feederVessel = details.feederVessel();
		this.plannedFeederDepartureAt = details.plannedFeederDepartureAt();
		recordVesselIdentity(details);
		markUpdated(updatedBy, updatedAt);
	}

	void recordVesselIdentity(ShipmentDetails details) {
		this.motherVesselImo = details.motherVesselImo();
		this.motherVesselMmsi = details.motherVesselMmsi();
		this.motherVesselOperator = details.motherVesselOperator();
		this.feederVesselImo = details.feederVesselImo();
		this.feederVesselMmsi = details.feederVesselMmsi();
		this.feederVesselOperator = details.feederVesselOperator();
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
	public String getMotherVesselImo() { return motherVesselImo; }
	public String getMotherVesselMmsi() { return motherVesselMmsi; }
	public String getMotherVesselOperator() { return motherVesselOperator; }
	public String getFeederVesselImo() { return feederVesselImo; }
	public String getFeederVesselMmsi() { return feederVesselMmsi; }
	public String getFeederVesselOperator() { return feederVesselOperator; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public UserAccount getUpdatedBy() { return updatedBy; }
	public Long getVersion() { return version; }

	void linkImporter(Company importerCompany) {
		this.importerCompany = importerCompany;
	}

	public Company getImporterCompany() {
		return importerCompany;
	}

	public Company getCompany() {
		return company;
	}
}
