package com.drift.backend.maritimechange;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class MaritimeChangeEventService {
	private static final int READ_CHUNK = 256;
	private static final int MAX_SCAN = 4096;

	private final MaritimeSnapshotObservationStore store;
	private final MaritimeSnapshotShipmentMatcher matcher;
	private final VisibleShipments visibleShipments;
	private final MaritimeCollectionHealth health;

	MaritimeChangeEventService(MaritimeSnapshotObservationStore store, MaritimeSnapshotShipmentMatcher matcher,
			VisibleShipments visibleShipments, MaritimeCollectionHealth health) {
		this.store = store;
		this.matcher = matcher;
		this.visibleShipments = visibleShipments;
		this.health = health;
	}

	@Transactional(readOnly = true)
	MaritimeChangeEventResponse get(long viewerCompanyId, long shipmentId, Instant detectedSince, int requestedLimit,
			String cursor) {
		Instant since = detectedSince == null ? Instant.EPOCH : detectedSince;
		int limit = Math.max(1, Math.min(requestedLimit, 100));
		Cursor position = decodeCursor(cursor);
		List<ShipmentMatchFacts> facts = visibleShipments.forCompany(viewerCompanyId);
		List<MaritimeChangeEventResponse.Event> events = new ArrayList<>();
		Instant scanRecordedAt = position.recordedAt();
		long scanId = position.id();
		Instant nextCursorAt = scanRecordedAt;
		long nextCursorId = scanId;
		int scanned = 0;
		boolean more = false;
		while (scanned < MAX_SCAN && !more) {
			int batchLimit = Math.min(READ_CHUNK, MAX_SCAN - scanned);
			List<MaritimeSnapshotObservation> rows = store.after(since, scanRecordedAt, scanId, batchLimit);
			if (rows.isEmpty()) break;
			for (MaritimeSnapshotObservation row : rows) {
				scanRecordedAt = row.getRecordedAt();
				scanId = row.getId();
				scanned++;
				MaritimeSnapshotChange change = asChange(row);
				MaritimeShipmentMatch match = matcher.match(viewerCompanyId, change, facts);
				MaritimeShipmentCandidate candidate = match.candidates().stream()
						.filter(item -> item.shipmentId() == shipmentId).findFirst().orElse(null);
				if (candidate == null) continue;
				if (events.size() == limit) {
					more = true;
					break;
				}
				events.add(toEvent(row, candidate));
				nextCursorAt = row.getRecordedAt();
				nextCursorId = row.getId();
			}
			if (rows.size() < batchLimit) break;
		}
		if (!more && scanned >= MAX_SCAN) {
			nextCursorAt = scanRecordedAt;
			nextCursorId = scanId;
			more = true;
		}
		String availability = health.availability();
		if ("UNAVAILABLE".equals(availability) && !events.isEmpty()) availability = "PARTIAL";
		return new MaritimeChangeEventResponse(availability, List.copyOf(events),
				more ? encodeCursor(nextCursorAt, nextCursorId) : null);
	}

	private static MaritimeSnapshotChange asChange(MaritimeSnapshotObservation row) {
		return new MaritimeSnapshotChange(row.getSourceType(), row.getSourceIdentity(), row.getContentFingerprint(),
				row.getChangeKind(), row.getOrdering(), row.isForwardLooking(), row.getProvenance(), row.getSourceEventAt(),
				row.getObservedAt(), row.getRetrievedAtUtc().toString(), row.getRetrievedAtUtc(), row.getSourceMode(),
				row.getFreshness(), row.getVesselImo(), row.getLocation(), string(row.getEffectiveFrom()),
				string(row.getEffectiveUntil()), row.getPreviousValue(), row.getCurrentValue(),
				row.getActivityCompatibility(), row.getLocationCompatibility(), row.getTimeCompatibility(), row.getRecordedAt());
	}

	private static MaritimeChangeEventResponse.Event toEvent(MaritimeSnapshotObservation row,
			MaritimeShipmentCandidate candidate) {
		String eventId = row.getSourceType() + ":" + row.getSourceIdentity() + ":" + row.getContentFingerprint();
		boolean review = !candidate.vesselSpecificImpact() || !"APPLICABLE".equals(row.getFreshness());
		return new MaritimeChangeEventResponse.Event(eventId, row.getSourceType(), row.getSourceIdentity(),
				row.getChangeKind(), row.getSourceEventAt(), row.getObservedAt(),
				row.getRetrievedAtUtc().toString(), row.getRecordedAt().toString(), row.getPreviousValue(),
				row.getCurrentValue(), row.getProvenance(), row.getSourceMode(), row.getFreshness(), candidate.association(),
				new MaritimeChangeEventResponse.Compatibility(candidate.activityCompatibility(),
						candidate.locationCompatibility(), candidate.timeCompatibility()), review);
	}

	private static String encodeCursor(Instant recordedAt, long id) {
		String value = recordedAt + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static Cursor decodeCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) return new Cursor(null, 0);
		try {
			String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = value.split("\\|", 2);
			if (parts.length != 2) throw new IllegalArgumentException("Malformed cursor");
			Instant recordedAt = Instant.parse(parts[0]);
			long id = Long.parseLong(parts[1]);
			if (id < 1) throw new IllegalArgumentException("Malformed cursor");
			return new Cursor(recordedAt, id);
		} catch (RuntimeException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid maritime change cursor");
		}
	}

	private static String string(Instant value) { return value == null ? null : value.toString(); }
	private record Cursor(Instant recordedAt, long id) { }
}
