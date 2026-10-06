package com.drift.backend.ais.position;

import java.math.BigDecimal;
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
@Table(name = "vessel_observations")
public class VesselObservation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 9)
	private String mmsi;

	@Column(name = "vessel_name", length = 200)
	private String vesselName;

	@Column(nullable = false)
	private BigDecimal latitude;

	@Column(nullable = false)
	private BigDecimal longitude;

	@Column(name = "speed_over_ground_knots")
	private BigDecimal speedOverGroundKnots;

	@Column(name = "course_over_ground_degrees")
	private BigDecimal courseOverGroundDegrees;

	@Column(name = "true_heading_degrees")
	private Integer trueHeadingDegrees;

	@Column(name = "navigational_status")
	private Integer navigationalStatus;

	@Column(name = "position_valid")
	private Boolean positionValid;

	@Column(name = "ais_utc_second")
	private Integer aisUtcSecond;

	@Column(name = "ingested_at", nullable = false)
	private Instant ingestedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private AisPositionSource source;

	protected VesselObservation() {
	}

	public VesselObservation(AisPosition position) {
		this.mmsi = position.mmsi();
		this.vesselName = position.vesselName();
		this.latitude = position.latitude();
		this.longitude = position.longitude();
		this.speedOverGroundKnots = position.speedOverGroundKnots();
		this.courseOverGroundDegrees = position.courseOverGroundDegrees();
		this.trueHeadingDegrees = position.trueHeadingDegrees();
		this.navigationalStatus = position.navigationalStatus();
		this.positionValid = position.positionValid();
		this.aisUtcSecond = position.aisUtcSecond();
		this.ingestedAt = position.ingestedAt();
		this.source = position.source();
	}

	AisPosition toAisPosition() {
		return new AisPosition(mmsi, vesselName, latitude, longitude, speedOverGroundKnots,
				courseOverGroundDegrees, trueHeadingDegrees, navigationalStatus, positionValid, aisUtcSecond,
				ingestedAt, source);
	}
}
