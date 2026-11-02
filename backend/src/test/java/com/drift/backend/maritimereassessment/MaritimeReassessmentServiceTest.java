package com.drift.backend.maritimereassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.maritimecontext.MaritimeContextResponse;
import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentService;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

@ExtendWith(MockitoExtension.class)
class MaritimeReassessmentServiceTest {

	@Mock ShipmentService shipments;
	@Mock MaritimeContextService context;
	@Mock ShipmentDetailResponse shipment;

	@Test
	void reviewUsesTheOwningCompanyAndDoesNotCallOutWhenProcessingIsClosed() {
		when(shipment.id()).thenReturn(8L);
		when(shipment.companyId()).thenReturn(3L);
		AuthenticatedUser importer = viewer(12L, Role.IMPORTER);
		when(shipments.get(importer, 8L)).thenReturn(shipment);
		Harness harness = new Harness();

		MaritimeReassessmentResult result = service(harness).review(importer, 8L, new MaritimeEvidenceBundle("recorded-mpa",
				Instant.parse("2026-10-28T00:00:00Z"), List.of(evidence()), List.of("eta-1")));

		assertThat(result.version().validationStatus()).isEqualTo("PENDING_REVIEW");
		assertThat(harness.store.history(8L)).singleElement().satisfies(row -> assertThat(row.owningCompanyId()).isEqualTo(3L));
		assertThat(harness.provider.calls.get()).isZero();
	}

	@Test
	void currentEvidenceFromTheMergedContextIsNotSentWhenTheBundleIsEmpty() {
		when(shipment.id()).thenReturn(8L);
		when(shipment.companyId()).thenReturn(3L);
		AuthenticatedUser owner = viewer(4L, Role.FREIGHT_FORWARDER);
		when(shipments.get(owner, 8L)).thenReturn(shipment);
		when(context.forShipment(shipment)).thenReturn(new MaritimeContextResponse(
				"cdg246.maritime-evidence-bundle.v1", false, "DISABLED", "2026-10-28T00:00:00Z", "UNAVAILABLE",
				"disabled", List.of(), List.of(), null, null));
		Harness harness = new Harness();

		MaritimeReassessmentResult result = service(harness).reviewCurrentEvidence(owner, 8L, "recorded-mpa");

		assertThat(result.version().validationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
		assertThat(result.alreadyRecorded()).isFalse();
		assertThat(service(harness).reviewCurrentEvidence(owner, 8L, "recorded-mpa").alreadyRecorded()).isTrue();
		assertThat(harness.provider.calls.get()).isZero();
		assertThat(result.version().interpretation()).isNull();
	}

	@Test
	void aHiddenShipmentIsNotReadOrReassessed() {
		AuthenticatedUser outsider = viewer(9L, Role.FREIGHT_FORWARDER);
		when(shipments.get(outsider, 8L)).thenThrow(new ShipmentDetailForbiddenException());
		Harness harness = new Harness();
		MaritimeReassessmentService reassessments = service(harness);

		assertThatThrownBy(() -> reassessments.history(outsider, 8L)).isInstanceOf(ShipmentDetailForbiddenException.class);
		assertThatThrownBy(() -> reassessments.review(outsider, 8L, new MaritimeEvidenceBundle("recorded-mpa",
				Instant.parse("2026-10-28T00:00:00Z"), List.of(), List.of()))).isInstanceOf(ShipmentDetailForbiddenException.class);
		assertThat(harness.store.history(8L)).isEmpty();
	}

	@Test
	void anUnknownShipmentIsNotFound() {
		AuthenticatedUser owner = viewer(4L, Role.FREIGHT_FORWARDER);
		when(shipments.get(owner, 44L)).thenThrow(new ShipmentNotFoundException());
		assertThatThrownBy(() -> service(new Harness()).history(owner, 44L)).isInstanceOf(ShipmentNotFoundException.class);
		verify(context, never()).forShipment(org.mockito.ArgumentMatchers.any());
	}

	private MaritimeReassessmentService service(Harness harness) {
		return new MaritimeReassessmentService(shipments, context, harness.orchestrator, harness.store);
	}

	private static AuthenticatedUser viewer(long id, Role role) {
		return new AuthenticatedUser(id, "viewer@example.com", role, Instant.parse("2026-11-02T00:00:00Z"));
	}

	private static MaritimeEvidence evidence() {
		return new MaritimeEvidence("MPA_ETA_REVISION", "RECORDED_REPLAY", "eta-1", "title", "source", "", "citation",
				"2026-10-29 02:00:00", "2026-10-26T15:46:03Z", "2026-10-26T15:46:03Z", "HISTORICAL", "before", "after",
				"+1", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", true, true, "UNVERIFIED_IMO_ONLY", "Recorded limitation.");
	}

	private static final class Harness {
		private final CountingProvider provider = new CountingProvider();
		private final MemoryStore store = new MemoryStore();
		private final MaritimeReassessmentOrchestrator orchestrator = new MaritimeReassessmentOrchestrator(store, provider,
				MaritimeReassessmentLimits.closed(), Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneId.of("UTC")));
	}

	private static final class CountingProvider implements MaritimeReassessmentProvider {
		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public boolean opensNetwork() { return true; }

		@Override
		public MaritimeProviderReply assess(MaritimeProviderRequest request) {
			calls.incrementAndGet();
			throw new IllegalStateException("closed");
		}
	}

	private static final class MemoryStore implements MaritimeReassessmentStore {
		private final List<StoredMaritimeReassessment> rows = new ArrayList<>();
		private final AtomicInteger ids = new AtomicInteger();

		@Override
		public <T> T exclusively(long shipmentId, String bundleHash, Supplier<T> work) { return work.get(); }

		@Override
		public List<StoredMaritimeReassessment> forBundle(long shipmentId, String bundleHash) {
			return rows.stream()
					.filter(row -> row.shipmentId() == shipmentId && bundleHash.equals(row.bundleHash()))
					.toList();
		}

		@Override
		public StoredMaritimeReassessment insert(MaritimeReassessmentAttempt attempt) {
			StoredMaritimeReassessment stored = attempt.storedWithId(ids.incrementAndGet());
			rows.add(stored);
			return stored;
		}

		@Override
		public List<StoredMaritimeReassessment> history(long shipmentId) {
			return rows.stream().filter(row -> row.shipmentId() == shipmentId).toList();
		}
	}
}
