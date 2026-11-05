package com.drift.backend.maritimereassessment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Records a shipment's reassessment for one evidence bundle.
 * A generated, failed, or rejected attempt is kept and not sent again.
 * A review that never left the process is checked again, and a later approved call is stored beside it.
 * External processing stays off unless the global gate, the dataset, and every source type are approved.
 * A call does not change the shipment, its schedule, risk, alerts, recommendations, or decisions.
 */
@Service
public class MaritimeReassessmentOrchestrator {

	static final String NONE = "NONE";
	private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

	private final MaritimeReassessmentStore store;
	private final MaritimeReassessmentProvider provider;
	private final MaritimeReassessmentLimits limits;
	private final Clock clock;
	private final Semaphore permits;
	private final Deque<Instant> calls = new ArrayDeque<>();

	@Autowired
	public MaritimeReassessmentOrchestrator(MaritimeReassessmentStore store, MaritimeReassessmentProvider provider,
			MaritimeReassessmentProperties properties) {
		this(store, provider, properties.toLimits(), Clock.systemUTC());
	}

	MaritimeReassessmentOrchestrator(MaritimeReassessmentStore store, MaritimeReassessmentProvider provider,
			MaritimeReassessmentLimits limits, Clock clock) {
		this.store = store;
		this.provider = provider;
		this.limits = limits;
		this.clock = clock;
		this.permits = limits.maxConcurrentRequests() < 1 ? null : new Semaphore(limits.maxConcurrentRequests());
	}

	public MaritimeReassessmentResult review(long shipmentId, long owningCompanyId, MaritimeEvidenceBundle bundle) {
		if (shipmentId <= 0 || owningCompanyId <= 0) {
			throw new IllegalArgumentException("Shipment and owning company are required");
		}
		MaritimeReassessmentBundle normalized = MaritimeReassessmentBundle.normalize(bundle);
		return store.exclusively(shipmentId, normalized.hash(), () -> reviewLocked(shipmentId, owningCompanyId, bundle.cutoff(), normalized));
	}

	private MaritimeReassessmentResult reviewLocked(long shipmentId, long owningCompanyId, Instant cutoff,
			MaritimeReassessmentBundle bundle) {
		List<StoredMaritimeReassessment> prior = store.forBundle(shipmentId, bundle.hash());
		for (int i = prior.size() - 1; i >= 0; i--) {
			if (settled(prior.get(i))) {
				return new MaritimeReassessmentResult(true, MaritimeReassessmentVersion.from(prior.get(i)));
			}
		}
		MaritimeReassessmentAttempt next = attempt(shipmentId, owningCompanyId, cutoff, bundle);
		if (!prior.isEmpty() && !recordsACall(next.storedWithId(0))) {
			return new MaritimeReassessmentResult(true, MaritimeReassessmentVersion.from(prior.get(prior.size() - 1)));
		}
		return new MaritimeReassessmentResult(false, MaritimeReassessmentVersion.from(store.insert(next)));
	}

	private static boolean settled(StoredMaritimeReassessment stored) {
		return recordsACall(stored) || "INSUFFICIENT_EVIDENCE".equals(stored.validationStatus());
	}

	private static boolean recordsACall(StoredMaritimeReassessment stored) {
		return "GENERATED".equals(stored.generationState())
				|| "FAILED".equals(stored.generationState())
				|| "INVALID".equals(stored.validationStatus());
	}

	private MaritimeReassessmentAttempt attempt(long shipmentId, long owningCompanyId, Instant cutoff,
			MaritimeReassessmentBundle bundle) {
		if (!bundle.hasCompatibleEvidence()) {
			return recorded(shipmentId, owningCompanyId, cutoff, bundle, "ABSTAINED", "INSUFFICIENT_EVIDENCE", NONE, NONE, NONE,
					null, "INSUFFICIENT_EVIDENCE: No compatible evidence is available for reassessment.");
		}
		String gate = gate(bundle);
		if (gate != null) {
			return recorded(shipmentId, owningCompanyId, cutoff, bundle, "ABSTAINED", "PENDING_REVIEW", NONE, NONE, NONE,
					null, gate);
		}
		ProviderCall call = callProvider(bundle);
		if (call.reply() == null) {
			String state = call.failed() ? "FAILED" : "ABSTAINED";
			return recorded(shipmentId, owningCompanyId, cutoff, bundle, state, call.validationStatus(), NONE, NONE, NONE,
					null, call.metadata());
		}
		String problem = MaritimeReassessmentValidator.problem(bundle, call.reply());
		if (problem != null) {
			return recorded(shipmentId, owningCompanyId, cutoff, bundle, "ABSTAINED", "INVALID", call.reply().provider(),
					bounded(call.reply().model(), 200), bounded(call.reply().promptVersion(), 100), null, problem);
		}
		return recorded(shipmentId, owningCompanyId, cutoff, bundle, "GENERATED", "VALID", call.reply().provider().trim(),
				call.reply().model().trim(), call.reply().promptVersion().trim(), call.reply().interpretation().trim(), null);
	}

	private String gate(MaritimeReassessmentBundle bundle) {
		if (!limits.externalProcessingEnabled()) {
			return "GLOBAL_GATE: External processing is off. Nothing was sent.";
		}
		if (!limits.approvedDatasets().contains(bundle.datasetId())) {
			return "DATASET_APPROVAL: This dataset is not approved for external processing. Nothing was sent.";
		}
		for (var item : bundle.included()) {
			if (!limits.approvedSourceTypes().contains(item.sourceType())) {
				return "SOURCE_ALLOWLIST: A source type is outside the approved allowlist. Nothing was sent.";
			}
		}
		return null;
	}

	private ProviderCall callProvider(MaritimeReassessmentBundle bundle) {
		if (limits.maxEstimatedCostUnits() < 1) {
			return ProviderCall.abstain("COST_LIMITED", "COST_LIMITED: The cost limit blocked the provider call. Nothing was sent.");
		}
		if (limits.maxCallsPerMinute() < 1) {
			return ProviderCall.abstain("RATE_LIMITED", "RATE_LIMITED: The rate limit blocked the provider call. Nothing was sent.");
		}
		if (limits.maxAttempts() < 1 || permits == null) {
			return ProviderCall.abstain("CONCURRENCY_LIMITED",
					"CONCURRENCY_LIMITED: The concurrency limit blocked the provider call. Nothing was sent.");
		}
		if (!provider.opensNetwork()) {
			return ProviderCall.abstain("PROVIDER_UNAVAILABLE",
					"PROVIDER_UNAVAILABLE: No network provider is configured. Nothing was sent.");
		}
		RuntimeException last = null;
		int attempts = 0;
		while (attempts < limits.maxAttempts() && attempts < limits.maxEstimatedCostUnits()) {
			if (!permits.tryAcquire()) {
				if (attempts == 0) {
					return ProviderCall.abstain("CONCURRENCY_LIMITED",
							"CONCURRENCY_LIMITED: The concurrency limit blocked the provider call. Nothing was sent.");
				}
				break;
			}
			try {
				if (!claimCall(clock.instant())) {
					if (attempts == 0) {
						return ProviderCall.abstain("RATE_LIMITED",
								"RATE_LIMITED: The rate limit blocked the provider call. Nothing was sent.");
					}
					break;
				}
				try {
					return ProviderCall.reply(invoke(bundle));
				} catch (MaritimeProviderTransientException ex) {
					attempts++;
					last = ex;
				} catch (RuntimeException ex) {
					return ProviderCall.fail("PROVIDER_FAILED: The provider failed. No interpretation was stored.");
				}
			} finally {
				permits.release();
			}
		}
		String detail = last == null ? "The provider failed. No interpretation was stored." : last.getMessage();
		return ProviderCall.fail("PROVIDER_FAILED: " + detail);
	}

	private MaritimeProviderReply invoke(MaritimeReassessmentBundle bundle) {
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "maritime-reassessment");
			thread.setDaemon(true);
			return thread;
		});
		Future<MaritimeProviderReply> future = executor.submit(() -> provider.assess(bundle.providerRequest(limits.callTimeout())));
		try {
			return future.get(limits.callTimeout().toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException ex) {
			future.cancel(true);
			throw new MaritimeProviderTransientException("The provider timed out. No interpretation was stored.");
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			throw new MaritimeProviderTransientException("The provider call was interrupted. No interpretation was stored.");
		} catch (ExecutionException ex) {
			future.cancel(true);
			Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof MaritimeProviderTransientException transientFailure) {
				throw transientFailure;
			}
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(cause);
		} finally {
			executor.shutdownNow();
		}
	}

	private boolean claimCall(Instant now) {
		synchronized (calls) {
			Instant cutoff = now.minus(RATE_WINDOW);
			while (!calls.isEmpty() && calls.peekFirst().isBefore(cutoff)) {
				calls.removeFirst();
			}
			if (calls.size() >= limits.maxCallsPerMinute()) {
				return false;
			}
			calls.addLast(now);
			return true;
		}
	}

	private MaritimeReassessmentAttempt recorded(long shipmentId, long owningCompanyId, Instant cutoff,
			MaritimeReassessmentBundle bundle, String generationState, String validationStatus, String provider,
			String model, String promptVersion, String interpretation, String failure) {
		return MaritimeReassessmentAttempt.create(shipmentId, owningCompanyId, UUID.randomUUID().toString(), bundle.hash(),
				cutoff, provider, model, promptVersion, generationState, validationStatus,
				MaritimeReassessmentLinkage.write(bundle, interpretation), truncate(failure), clock.instant());
	}

	private static String bounded(String value, int max) {
		if (value == null || value.isBlank() || value.length() > max) {
			return NONE;
		}
		return value.trim();
	}

	private static String truncate(String value) {
		if (value == null || value.length() <= 4000) {
			return value;
		}
		return value.substring(0, 4000);
	}

	private record ProviderCall(MaritimeProviderReply reply, boolean failed, String validationStatus, String metadata) {
		static ProviderCall reply(MaritimeProviderReply reply) {
			return new ProviderCall(reply, false, null, null);
		}

		static ProviderCall abstain(String status, String metadata) {
			return new ProviderCall(null, false, status, metadata);
		}

		static ProviderCall fail(String metadata) {
			return new ProviderCall(null, true, "PROVIDER_FAILED", metadata);
		}
	}
}
