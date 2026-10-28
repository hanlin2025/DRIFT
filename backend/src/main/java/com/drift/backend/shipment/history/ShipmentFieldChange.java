package com.drift.backend.shipment.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One field's previous and new value within a shipment change event, stored as text snapshots. */
@Entity
@Table(name = "shipment_field_changes")
public class ShipmentFieldChange {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id", nullable = false)
	private ShipmentChangeEvent event;

	@Column(name = "field_name", nullable = false, length = 64)
	private String fieldName;

	@Column(name = "previous_value")
	private String previousValue;

	@Column(name = "new_value")
	private String newValue;

	protected ShipmentFieldChange() {
	}

	ShipmentFieldChange(ShipmentChangeEvent event, String fieldName, String previousValue, String newValue) {
		this.event = event;
		this.fieldName = fieldName;
		this.previousValue = previousValue;
		this.newValue = newValue;
	}

	public Long getId() {
		return id;
	}

	public ShipmentChangeEvent getEvent() {
		return event;
	}

	public String getFieldName() {
		return fieldName;
	}

	public String getPreviousValue() {
		return previousValue;
	}

	public String getNewValue() {
		return newValue;
	}
}
