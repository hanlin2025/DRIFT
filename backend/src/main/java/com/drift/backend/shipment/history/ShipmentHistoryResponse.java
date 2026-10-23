package com.drift.backend.shipment.history;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ShipmentHistory", description = "A shipment's recorded changes, newest first. The creation is the last entry.")
public record ShipmentHistoryResponse(
		@Schema(example = "42") Long shipmentId,
		@Schema(description = "True when the shipment was changed before history was recorded, so earlier changes are missing.",
				example = "false")
		boolean earlierChangesUnrecorded,
		List<Entry> entries) {

	static ShipmentHistoryResponse from(Long shipmentId, List<ShipmentChangeEvent> events) {
		return new ShipmentHistoryResponse(shipmentId,
				events.stream().anyMatch(ShipmentChangeEvent::isEarlierChangesUnrecorded),
				events.stream().map(Entry::from).toList());
	}

	@Schema(name = "ShipmentHistoryEntry", description = "One recorded change to a shipment.")
	public record Entry(
			@Schema(example = "7") Long id,
			@Schema(example = "UPDATED") ShipmentChangeType changeType,
			Actor changedBy,
			@Schema(example = "2026-10-21T04:52:22.192648Z") Instant recordedAt,
			@Schema(description = "Fields this change set. Empty for a creation.") List<FieldChange> changes) {

		static Entry from(ShipmentChangeEvent event) {
			return new Entry(event.getId(), event.getChangeType(), Actor.from(event),
					event.getRecordedAt(), event.getFieldChanges().stream().map(FieldChange::from).toList());
		}
	}

	@Schema(name = "ShipmentHistoryActor", description = "The user who made the change.")
	public record Actor(@Schema(example = "3") Long id, @Schema(example = "Alice Tan") String fullName) {

		static Actor from(ShipmentChangeEvent event) {
			return new Actor(event.getActor().getId(), event.getActor().getFullName());
		}
	}

	@Schema(name = "ShipmentHistoryFieldChange", description = "One field's value before and after the change. "
			+ "Times are UTC instants and the importer is the company name at the time of the change.")
	public record FieldChange(
			@Schema(example = "plannedFeederDepartureAt") String field,
			@Schema(nullable = true, example = "2026-10-16T04:00:00Z") String previousValue,
			@Schema(nullable = true, example = "2026-10-17T01:30:00Z") String newValue) {

		static FieldChange from(ShipmentFieldChange change) {
			return new FieldChange(change.getFieldName(), change.getPreviousValue(), change.getNewValue());
		}
	}
}
