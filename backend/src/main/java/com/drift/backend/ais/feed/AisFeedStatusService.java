package com.drift.backend.ais.feed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.VesselObservationRepository;

/** Classifies the AIS feed from the newest stored AIS_STREAM report. Reading does not change that timestamp. */
@Service
public class AisFeedStatusService {

	static final Duration LIVE_FOR = Duration.ofMinutes(5);
	static final Duration DELAYED_FOR = Duration.ofHours(1);

	private final VesselObservationRepository observations;
	private final Clock clock;

	public AisFeedStatusService(VesselObservationRepository observations, ObjectProvider<Clock> clocks) {
		this.observations = observations;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Transactional(readOnly = true)
	public AisFeedStatus current() {
		return observations.findFirstBySourceOrderByIngestedAtDescIdDesc(AisPositionSource.AIS_STREAM)
				.map(observation -> classify(observation.ingestedAt()))
				.orElseGet(() -> new AisFeedStatus(AisFeedStatus.Status.OFFLINE, null));
	}

	private AisFeedStatus classify(Instant lastReceivedAt) {
		Duration age = Duration.between(lastReceivedAt, Instant.now(clock));
		if (age.compareTo(LIVE_FOR) <= 0) {
			return new AisFeedStatus(AisFeedStatus.Status.LIVE, lastReceivedAt);
		}
		if (age.compareTo(DELAYED_FOR) <= 0) {
			return new AisFeedStatus(AisFeedStatus.Status.DELAYED, lastReceivedAt);
		}
		return new AisFeedStatus(AisFeedStatus.Status.OFFLINE, lastReceivedAt);
	}
}
