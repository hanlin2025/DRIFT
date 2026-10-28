package com.drift.backend.shipment.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.drift.backend.account.UserAccount;
import com.drift.backend.shipment.Shipment;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** One recorded change to a shipment: who made it, when, and which fields changed. */
@Entity
@Table(name = "shipment_change_events")
public class ShipmentChangeEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shipment_id", nullable = false)
	private Shipment shipment;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "actor_user_id", nullable = false)
	private UserAccount actor;

	@Enumerated(EnumType.STRING)
	@Column(name = "change_type", nullable = false, length = 32)
	private ShipmentChangeType changeType;

	@Column(name = "earlier_changes_unrecorded", nullable = false)
	private boolean earlierChangesUnrecorded;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	@OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id ASC")
	private List<ShipmentFieldChange> fieldChanges = new ArrayList<>();

	protected ShipmentChangeEvent() {
	}

	public ShipmentChangeEvent(Shipment shipment, UserAccount actor, ShipmentChangeType changeType,
			Instant recordedAt) {
		this.shipment = shipment;
		this.actor = actor;
		this.changeType = changeType;
		this.recordedAt = recordedAt;
	}

	public void addFieldChange(String fieldName, String previousValue, String newValue) {
		fieldChanges.add(new ShipmentFieldChange(this, fieldName, previousValue, newValue));
	}

	public Long getId() {
		return id;
	}

	public Shipment getShipment() {
		return shipment;
	}

	public UserAccount getActor() {
		return actor;
	}

	public ShipmentChangeType getChangeType() {
		return changeType;
	}

	/** True only for a creation entry backfilled for a shipment that was already changed before history began. */
	public boolean isEarlierChangesUnrecorded() {
		return earlierChangesUnrecorded;
	}

	public Instant getRecordedAt() {
		return recordedAt;
	}

	public List<ShipmentFieldChange> getFieldChanges() {
		return List.copyOf(fieldChanges);
	}
}
