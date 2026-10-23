package com.drift.backend.shipment.history;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.Shipment;
import com.drift.backend.shipment.ShipmentStatus;

/**
 * Writes a shipment's change history. Callers record a change only after it is saved, inside the same
 * transaction, so a rejected or rolled-back change leaves no entry.
 */
@Component
public class ShipmentHistoryRecorder {

	static final String IMPORTER_FIELD = "importer";
	static final String STATUS_FIELD = "status";

	private final ShipmentChangeEventRepository events;

	public ShipmentHistoryRecorder(ShipmentChangeEventRepository events) {
		this.events = events;
	}

	public void recordCreation(Shipment shipment, UserAccount actor) {
		events.save(new ShipmentChangeEvent(shipment, actor, ShipmentChangeType.CREATED, shipment.getCreatedAt()));
	}

	/** Records only the fields that differ from {@code before}; an edit that changes nothing writes no entry. */
	public void recordUpdate(ShipmentSnapshot before, Shipment shipment, UserAccount actor, Instant recordedAt) {
		ShipmentChangeEvent event = new ShipmentChangeEvent(shipment, actor, ShipmentChangeType.UPDATED, recordedAt);
		Map<String, String> after = ShipmentSnapshot.of(shipment).values();
		before.values().forEach((field, previous) -> {
			String current = after.get(field);
			if (!Objects.equals(previous, current)) {
				event.addFieldChange(field, previous, current);
			}
		});
		if (!event.getFieldChanges().isEmpty()) {
			events.save(event);
		}
	}

	/** Records linking, replacing or unlinking the importer, by company name at the time of the change. */
	public void recordImporterLink(Shipment shipment, Company previousImporter, UserAccount actor, Instant recordedAt) {
		String previous = name(previousImporter);
		String current = name(shipment.getImporterCompany());
		if (Objects.equals(previous, current)) {
			return;
		}
		ShipmentChangeEvent event = new ShipmentChangeEvent(shipment, actor, ShipmentChangeType.IMPORTER_LINKED,
				recordedAt);
		event.addFieldChange(IMPORTER_FIELD, previous, current);
		events.save(event);
	}

	/** Records an archive or a restore; the shipment's current status decides which. */
	public void recordStatusChange(Shipment shipment, UserAccount actor, ShipmentStatus previousStatus,
			Instant recordedAt) {
		ShipmentStatus current = shipment.getStatus();
		if (current == previousStatus) {
			return;
		}
		ShipmentChangeType type = current == ShipmentStatus.ARCHIVED
				? ShipmentChangeType.ARCHIVED
				: ShipmentChangeType.RESTORED;
		ShipmentChangeEvent event = new ShipmentChangeEvent(shipment, actor, type, recordedAt);
		event.addFieldChange(STATUS_FIELD, previousStatus.name(), current.name());
		events.save(event);
	}

	private static String name(Company company) {
		return company == null ? null : company.getName();
	}
}
