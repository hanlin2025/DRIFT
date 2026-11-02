package com.drift.backend.maritimereassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimecontext.MaritimeEvidence;

class MaritimeReassessmentOrchestratorTest {

	private static final Instant CUTOFF = Instant.parse("2026-10-28T00:00:00Z");

	@Test
	void anUnapprovedBundleIsRecordedOnceAndDoesNotCallTheProvider() {
		CountingProvider provider = new CountingProvider(reply("UNVERIFIED_IMO_ONLY"));
		MemoryStore store = new MemoryStore();
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(store, provider, MaritimeReassessmentLimits.closed());

		MaritimeReassessmentResult first = orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));
		MaritimeReassessmentResult second = orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));

		assertThat(provider.calls.get()).isZero();
		assertThat(first.alreadyRecorded()).isFalse();
		assertThat(second.alreadyRecorded()).isTrue();
		assertThat(second.version().assessmentId()).isEqualTo(first.version().assessmentId());
		assertThat(first.version().generationState()).isEqualTo("ABSTAINED");
		assertThat(first.version().validationStatus()).isEqualTo("PENDING_REVIEW");
		assertThat(first.version().failureMetadata()).contains("GLOBAL_GATE");
		assertThat(first.version().interpretation()).isNull();
		assertThat(first.version().forwardLooking()).isFalse();
		assertThat(store.history(8L)).hasSize(1);
	}

	@Test
	void datasetAndSourceGatesBlockBeforeAnyCall() {
		CountingProvider provider = new CountingProvider(reply("UNVERIFIED_IMO_ONLY"));
		MemoryStore store = new MemoryStore();
		MaritimeReassessmentOrchestrator datasets = orchestrator(store, provider, open(Set.of(), Set.of("MPA_ETA_REVISION")));
		MaritimeReassessmentResult dataset = datasets.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));
		assertThat(dataset.version().failureMetadata()).contains("DATASET_APPROVAL");

		MaritimeReassessmentOrchestrator sources = orchestrator(new MemoryStore(), provider, open(Set.of("recorded-mpa"), Set.of("NEA_WEATHER")));
		MaritimeReassessmentResult source = sources.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));
		assertThat(source.version().failureMetadata()).contains("SOURCE_ALLOWLIST");
		assertThat(provider.calls.get()).isZero();
	}

	@Test
	void anApprovedReplayIsStoredOnceAndARepeatDoesNotCallAgain() {
		CountingProvider provider = new CountingProvider(reply("UNVERIFIED_IMO_ONLY"));
		MemoryStore store = new MemoryStore();
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(store, provider, open());
		MaritimeEvidence firstRead = evidence("RECORDED_REPLAY", "HISTORICAL", "before", "2026-10-26T15:46:03Z");
		MaritimeEvidence secondRead = evidence("RECORDED_REPLAY", "HISTORICAL", "before", "2026-10-27T01:00:00Z");

		MaritimeReassessmentResult first = orchestrator.review(8L, 3L, bundle("recorded-mpa", firstRead));
		MaritimeReassessmentResult second = orchestrator.review(8L, 3L, bundle("recorded-mpa", secondRead));

		assertThat(provider.calls.get()).isEqualTo(1);
		assertThat(provider.requests).singleElement().satisfies(request -> {
			assertThat(request.datasetId()).isEqualTo("recorded-mpa");
			assertThat(request.snapshotIds()).containsExactly("eta-1");
			assertThat(request.evidence()).extracting(MaritimeProviderRequest.MaritimeProviderEvidence::evidenceId).containsExactly("eta-1");
		});
		assertThat(second.alreadyRecorded()).isTrue();
		assertThat(first.version().validationStatus()).isEqualTo("VALID");
		assertThat(first.version().generationState()).isEqualTo("GENERATED");
		assertThat(first.version().provider()).isEqualTo("test-provider");
		assertThat(first.version().model()).isEqualTo("test-model");
		assertThat(first.version().promptVersion()).isEqualTo("prompt-1");
		assertThat(first.version().interpretation()).isEqualTo("The recorded revision is unverified.");
		assertThat(first.version().forwardLooking()).isFalse();
		assertThat(first.version().sourceFreshness()).isEqualTo("HISTORICAL");
		assertThat(first.version().snapshotIds()).containsExactly("eta-1");
		assertThat(first.version().bundleHash()).hasSize(64);
		assertThat(store.history(8L)).hasSize(1);
	}

	@Test
	void anImoOnlyResultCannotBecomeVerifiedAndWeatherOrBerthClaimsAreRejected() {
		MemoryStore store = new MemoryStore();
		MaritimeReassessmentOrchestrator upgraded = orchestrator(store, new CountingProvider(reply("VERIFIED_ASSOCIATION")), open());
		MaritimeReassessmentResult identity = upgraded.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));
		assertThat(identity.version().validationStatus()).isEqualTo("INVALID");
		assertThat(identity.version().failureMetadata()).contains("ASSOCIATION_UPGRADED");
		assertThat(identity.version().interpretation()).isNull();

		MaritimeReassessmentOrchestrator weather = orchestrator(new MemoryStore(), new CountingProvider(reply("UNVERIFIED_IMO_ONLY", true, false)), open());
		assertThat(weather.review(8L, 3L, bundle("recorded-mpa", evidence("LIVE", "APPLICABLE", "before", "NEA_WEATHER", "weather-1"))).version().failureMetadata())
				.contains("WEATHER_CAUSATION");

		MaritimeReassessmentOrchestrator berth = orchestrator(new MemoryStore(), new CountingProvider(reply("UNVERIFIED_IMO_ONLY", false, true)), open());
		assertThat(berth.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before"))).version().failureMetadata())
				.contains("SPECIFIC_BERTH");
	}

	@Test
	void incompatibleEvidenceIsLeftOutAndAnEmptyCompatibleBundleIsNotSent() {
		CountingProvider provider = new CountingProvider(reply("UNVERIFIED_IMO_ONLY"));
		MemoryStore store = new MemoryStore();
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(store, provider, open());
		MaritimeEvidence compatible = evidence("RECORDED_REPLAY", "HISTORICAL", "before");
		MaritimeEvidence incompatible = evidence("SYNTHETIC_DEMONSTRATION", "SYNTHETIC_DEMONSTRATION", "", "MPA_OFFICIAL_NOTICE", "notice-1", "INCOMPATIBLE");

		orchestrator.review(8L, 3L, bundle("recorded-mpa", List.of(incompatible, compatible), List.of("eta-1")));
		MaritimeReassessmentResult repeat = orchestrator.review(8L, 3L, bundle("recorded-mpa", List.of(compatible), List.of("eta-1")));

		assertThat(provider.calls.get()).isEqualTo(1);
		assertThat(provider.requests).singleElement().satisfies(request ->
				assertThat(request.evidence()).extracting(MaritimeProviderRequest.MaritimeProviderEvidence::evidenceId).containsExactly("eta-1"));
		assertThat(repeat.alreadyRecorded()).isTrue();

		MaritimeReassessmentResult empty = orchestrator.review(9L, 3L, bundle("recorded-mpa", List.of(incompatible), List.of()));
		assertThat(empty.version().validationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
		assertThat(provider.calls.get()).isEqualTo(1);
	}

	@Test
	void aLiveApplicableBundleIsForwardLookingAndABackfillIsNot() {
		CountingProvider provider = new CountingProvider(reply("UNVERIFIED_IMO_ONLY"));
		MemoryStore store = new MemoryStore();
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(store, provider, open());

		MaritimeReassessmentResult live = orchestrator.review(8L, 3L, bundle("recorded-mpa",
				evidence("LIVE", "APPLICABLE", "before")));
		MaritimeReassessmentResult backfill = orchestrator.review(8L, 3L, bundle("recorded-mpa",
				evidence("HISTORICAL_BACKFILL", "HISTORICAL", "before", "MPA_ETA_REVISION", "eta-2")));

		assertThat(live.version().forwardLooking()).isTrue();
		assertThat(backfill.version().forwardLooking()).isFalse();
		assertThat(backfill.version().bundleHash()).isNotEqualTo(live.version().bundleHash());
	}

	@Test
	void aTransientFailureIsTriedOnceAndTheSameBundleIsNotTriedAgain() {
		CountingProvider provider = new CountingProvider(new MaritimeProviderTransientException("The provider timed out. No interpretation was stored."));
		MemoryStore store = new MemoryStore();
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(store, provider, open(2, 4, 2, 1));

		MaritimeReassessmentResult failed = orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));
		orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));

		assertThat(provider.calls.get()).isEqualTo(2);
		assertThat(failed.version().generationState()).isEqualTo("FAILED");
		assertThat(failed.version().validationStatus()).isEqualTo("PROVIDER_FAILED");
		assertThat(failed.version().interpretation()).isNull();
		assertThat(store.history(8L)).hasSize(1);
	}

	@Test
	void aPermanentFailureIsNotRetriedAndCostStopsASecondAttempt() {
		CountingProvider provider = new CountingProvider(new IllegalStateException("rejected"));
		MaritimeReassessmentOrchestrator permanent = orchestrator(new MemoryStore(), provider, open());
		permanent.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));
		assertThat(provider.calls.get()).isEqualTo(1);

		CountingProvider limited = new CountingProvider(new MaritimeProviderTransientException("again"));
		MaritimeReassessmentOrchestrator costs = orchestrator(new MemoryStore(), limited, open(1, 4, 2, 1));
		MaritimeReassessmentResult once = costs.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));
		assertThat(limited.calls.get()).isEqualTo(1);
		assertThat(once.version().generationState()).isEqualTo("FAILED");
	}

	@Test
	void theRateLimitBlocksASecondBundleWithoutAnotherCall() {
		CountingProvider provider = new CountingProvider(reply("UNVERIFIED_IMO_ONLY"));
		MemoryStore store = new MemoryStore();
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(store, provider, open(2, 1, 2, 1));

		orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));
		MaritimeReassessmentResult limited = orchestrator.review(8L, 3L, bundle("recorded-mpa",
				evidence("RECORDED_REPLAY", "HISTORICAL", "after", "MPA_ETA_REVISION", "eta-2")));

		assertThat(provider.calls.get()).isEqualTo(1);
		assertThat(limited.version().validationStatus()).isEqualTo("RATE_LIMITED");
		assertThat(limited.version().generationState()).isEqualTo("ABSTAINED");
	}

	@Test
	void aSecondConcurrentBundleDoesNotPassTheSinglePermit() throws Exception {
		BlockingProvider provider = new BlockingProvider();
		MemoryStore store = new MemoryStore();
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(store, provider, open());
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<MaritimeReassessmentResult> first = pool.submit(() -> orchestrator.review(8L, 3L,
					bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before"))));
			assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
			MaritimeReassessmentResult second = orchestrator.review(9L, 3L,
					bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "after", "MPA_ETA_REVISION", "eta-2")));
			provider.release.countDown();
			MaritimeReassessmentResult completed = first.get(2, TimeUnit.SECONDS);

			assertThat(provider.calls.get()).isEqualTo(1);
			assertThat(completed.version().validationStatus()).isEqualTo("VALID");
			assertThat(second.version().validationStatus()).isEqualTo("CONCURRENCY_LIMITED");
		} finally {
			provider.release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void aTimedOutProviderIsAFailedAttemptAndDoesNotInventAnInterpretation() {
		CountingProvider provider = new CountingProvider((MaritimeProviderRequest request) -> {
			try {
				Thread.sleep(300);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new MaritimeProviderTransientException("interrupted");
			}
			return reply("UNVERIFIED_IMO_ONLY");
		});
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(new MemoryStore(), provider,
				new MaritimeReassessmentLimits(true, Set.of("recorded-mpa"), Set.of("MPA_ETA_REVISION"), 1, 2, 1, Duration.ofMillis(40), 1));

		MaritimeReassessmentResult result = orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));

		assertThat(result.version().generationState()).isEqualTo("FAILED");
		assertThat(result.version().interpretation()).isNull();
		assertThat(result.version().failureMetadata()).contains("timed out");
	}

	@Test
	void anApprovedBuildWithoutANetworkProviderRecordsThatNothingWasSent() {
		NoNetworkMaritimeReassessmentProvider provider = new NoNetworkMaritimeReassessmentProvider();
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(new MemoryStore(), provider, open());

		MaritimeReassessmentResult result = orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));

		assertThat(provider.wasCalled()).isFalse();
		assertThat(result.version().validationStatus()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(result.version().interpretation()).isNull();
	}

	@Test
	void aLaterFailureStaysBehindTheLatestValidatedAssessment() {
		MutableClock clock = new MutableClock(CUTOFF);
		MemoryStore store = new MemoryStore();
		AtomicReference<MaritimeProviderReply> reply = new AtomicReference<>(reply("UNVERIFIED_IMO_ONLY"));
		CountingProvider provider = new CountingProvider(request -> reply.get());
		MaritimeReassessmentOrchestrator orchestrator = new MaritimeReassessmentOrchestrator(store, provider, open(), clock);

		orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before")));
		clock.instant = clock.instant.plusSeconds(60);
		orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "after", "MPA_ETA_REVISION", "eta-2")));
		clock.instant = clock.instant.plusSeconds(60);
		reply.set(null);
		provider.failure = new IllegalStateException("down");
		orchestrator.review(8L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "later", "MPA_ETA_REVISION", "eta-3")));

		MaritimeReassessmentHistory history = MaritimeReassessmentHistory.of(8L, store.history(8L));
		assertThat(history.versions()).hasSize(3);
		assertThat(history.latestAttempt().validationStatus()).isEqualTo("PROVIDER_FAILED");
		assertThat(history.latestValidated().snapshotIds()).containsExactly("eta-2");
		assertThat(history.latestValidated().validationStatus()).isEqualTo("VALID");
		assertThat(history.versions()).extracting(MaritimeReassessmentVersion::validationStatus)
				.contains("VALID", "PROVIDER_FAILED");
		assertThat(history.latestValidated().assessmentId()).isNotEqualTo(history.latestAttempt().assessmentId());
	}

	@Test
	void aForeignOutputRemainsReadableWithoutBeingTreatedAsOurInterpretation() {
		StoredMaritimeReassessment foreign = new StoredMaritimeReassessment(4L, 8L, 3L, "assessment", "other", "a".repeat(64),
				CUTOFF, "other-provider", "other-model", "other-prompt", "GENERATED", "VALID",
				"{\"explanation\":\"keep me\"}", null, CUTOFF.plusSeconds(5));

		MaritimeReassessmentVersion version = MaritimeReassessmentVersion.from(foreign);

		assertThat(version.outputJson()).contains("keep me");
		assertThat(version.interpretation()).isNull();
		assertThat(version.snapshotIds()).isEmpty();
		assertThat(version.sourceFreshness()).isEqualTo("UNKNOWN");
		assertThat(version.provider()).isEqualTo("other-provider");
		assertThat(version.validated()).isTrue();
	}

	@Test
	void aMalformedBundleIsRejected() {
		MaritimeReassessmentOrchestrator orchestrator = orchestrator(new MemoryStore(), new CountingProvider(reply("UNVERIFIED_IMO_ONLY")), open());
		assertThatThrownBy(() -> orchestrator.review(8L, 3L, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> orchestrator.review(8L, 3L, new MaritimeEvidenceBundle(" ", CUTOFF, List.of(), List.of())))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> orchestrator.review(8L, 3L, new MaritimeEvidenceBundle("recorded-mpa", null, List.of(), List.of())))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> orchestrator.review(0L, 3L, bundle("recorded-mpa", evidence("RECORDED_REPLAY", "HISTORICAL", "before"))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theHistoryEndpointDoesNotStartAReassessment() {
		assertThat(MaritimeReassessmentController.class.getDeclaredMethods())
				.noneMatch(method -> method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) != null
						|| method.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class) != null
						|| method.getAnnotation(org.springframework.web.bind.annotation.PatchMapping.class) != null);
		assertThat(new MaritimeReassessmentProperties().toLimits().externalProcessingEnabled()).isFalse();
		assertThat(new MaritimeReassessmentProperties().toLimits().approvedDatasets()).isEmpty();
		assertThat(new MaritimeReassessmentProperties().toLimits().approvedSourceTypes()).isEmpty();
	}

	private static MaritimeReassessmentOrchestrator orchestrator(MemoryStore store, MaritimeReassessmentProvider provider,
			MaritimeReassessmentLimits limits) {
		return new MaritimeReassessmentOrchestrator(store, provider, limits, Clock.fixed(CUTOFF, ZoneId.of("UTC")));
	}

	private static MaritimeReassessmentLimits open() {
		return open(2, 4, 2, 1);
	}

	private static MaritimeReassessmentLimits open(Set<String> datasets, Set<String> sourceTypes) {
		return new MaritimeReassessmentLimits(true, datasets, sourceTypes, 1, 4, 2, Duration.ofSeconds(2), 2);
	}

	private static MaritimeReassessmentLimits open(int cost, int callsPerMinute, int attempts, int concurrent) {
		return new MaritimeReassessmentLimits(true, Set.of("recorded-mpa"), Set.of("MPA_ETA_REVISION", "MPA_OFFICIAL_NOTICE", "NEA_WEATHER"),
				concurrent, callsPerMinute, cost, Duration.ofSeconds(2), attempts);
	}

	private static MaritimeEvidenceBundle bundle(String dataset, MaritimeEvidence evidence) {
		return bundle(dataset, List.of(evidence), List.of(evidence.evidenceId()));
	}

	private static MaritimeEvidenceBundle bundle(String dataset, List<MaritimeEvidence> evidence, List<String> snapshots) {
		return new MaritimeEvidenceBundle(dataset, CUTOFF, evidence, snapshots);
	}

	private static MaritimeEvidence evidence(String mode, String freshness, String current) {
		return evidence(mode, freshness, current, "MPA_ETA_REVISION", "eta-1");
	}

	private static MaritimeEvidence evidence(String mode, String freshness, String current, String retrievedAt) {
		return evidence(mode, freshness, current, "MPA_ETA_REVISION", "eta-1", "UNKNOWN", retrievedAt);
	}

	private static MaritimeEvidence evidence(String mode, String freshness, String current, String sourceType, String evidenceId) {
		return evidence(mode, freshness, current, sourceType, evidenceId, "UNKNOWN", "2026-10-26T15:46:03Z");
	}

	private static MaritimeEvidence evidence(String mode, String freshness, String current, String sourceType, String evidenceId,
			String compatibility) {
		return evidence(mode, freshness, current, sourceType, evidenceId, compatibility, "2026-10-26T15:46:03Z");
	}

	private static MaritimeEvidence evidence(String mode, String freshness, String current, String sourceType, String evidenceId,
			String compatibility, String retrievedAt) {
		return new MaritimeEvidence(sourceType, mode, evidenceId, "title", "source", "", "citation",
				"2026-10-29 02:00:00", "2026-10-26T15:46:03Z", retrievedAt, freshness, "before", current, "+1",
				"UNKNOWN", "UNKNOWN", "UNKNOWN", compatibility, true, true, "UNVERIFIED_IMO_ONLY", "Recorded limitation.");
	}

	private static MaritimeProviderReply reply(String association) {
		return reply(association, false, false);
	}

	private static MaritimeProviderReply reply(String association, boolean weather, boolean berth) {
		return new MaritimeProviderReply("test-provider", "test-model", "prompt-1", "The recorded revision is unverified.",
				association, weather, berth);
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() { return ZoneId.of("UTC"); }

		@Override
		public Clock withZone(ZoneId zone) { return this; }

		@Override
		public Instant instant() { return instant; }
	}

	private static final class CountingProvider implements MaritimeReassessmentProvider {
		private final java.util.function.Function<MaritimeProviderRequest, MaritimeProviderReply> responder;
		private final AtomicInteger calls = new AtomicInteger();
		private final List<MaritimeProviderRequest> requests = new ArrayList<>();
		private RuntimeException failure;

		private CountingProvider(MaritimeProviderReply reply) {
			this(request -> reply);
		}

		private CountingProvider(RuntimeException failure) {
			this(request -> {
				throw failure;
			});
		}

		private CountingProvider(java.util.function.Function<MaritimeProviderRequest, MaritimeProviderReply> responder) {
			this.responder = responder;
		}

		@Override
		public boolean opensNetwork() { return true; }

		@Override
		public MaritimeProviderReply assess(MaritimeProviderRequest request) {
			calls.incrementAndGet();
			requests.add(request);
			if (failure != null) {
				throw failure;
			}
			MaritimeProviderReply reply = responder.apply(request);
			if (reply == null) {
				throw new IllegalStateException("down");
			}
			return reply;
		}
	}

	private static final class BlockingProvider implements MaritimeReassessmentProvider {
		private final AtomicInteger calls = new AtomicInteger();
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		@Override
		public boolean opensNetwork() { return true; }

		@Override
		public MaritimeProviderReply assess(MaritimeProviderRequest request) {
			calls.incrementAndGet();
			started.countDown();
			try {
				if (!release.await(2, TimeUnit.SECONDS)) {
					throw new IllegalStateException("not released");
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new MaritimeProviderTransientException("interrupted");
			}
			return reply("UNVERIFIED_IMO_ONLY");
		}
	}

	private static final class MemoryStore implements MaritimeReassessmentStore {
		private final ConcurrentHashMap<String, StoredMaritimeReassessment> rows = new ConcurrentHashMap<>();
		private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
		private final AtomicInteger ids = new AtomicInteger();

		@Override
		public <T> T exclusively(long shipmentId, String bundleHash, Supplier<T> work) {
			synchronized (locks.computeIfAbsent(shipmentId + ":" + bundleHash, key -> new Object())) {
				return work.get();
			}
		}

		@Override
		public Optional<StoredMaritimeReassessment> find(long shipmentId, String bundleHash) {
			return Optional.ofNullable(rows.get(shipmentId + ":" + bundleHash));
		}

		@Override
		public StoredMaritimeReassessment insert(MaritimeReassessmentAttempt attempt) {
			StoredMaritimeReassessment stored = attempt.storedWithId(ids.incrementAndGet());
			rows.put(stored.shipmentId() + ":" + stored.bundleHash(), stored);
			return stored;
		}

		@Override
		public List<StoredMaritimeReassessment> history(long shipmentId) {
			return rows.values().stream().filter(row -> row.shipmentId() == shipmentId)
					.sorted((left, right) -> {
						int byTime = right.createdAt().compareTo(left.createdAt());
						return byTime != 0 ? byTime : Long.compare(right.id(), left.id());
					})
					.toList();
		}
	}
}
