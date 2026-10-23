package com.drift.backend.ais.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.drift.backend.ais.feed.AisFeedStatus.Status;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.VesselObservation;
import com.drift.backend.ais.position.VesselObservationRepository;

@ExtendWith(MockitoExtension.class)
class AisFeedStatusServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-22T06:00:00Z");

	@Mock private VesselObservationRepository observations;
	@Mock private ObjectProvider<Clock> clocks;

	private AisFeedStatusService feeds;

	@BeforeEach
	void service() {
		when(clocks.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
		feeds = new AisFeedStatusService(observations, clocks);
	}

	@Test
	void isLiveAtFiveMinutesAndDelayedUntilOneHour() {
		assertThat(status(NOW.minus(Duration.ofMinutes(5))).status()).isEqualTo(Status.LIVE);
		assertThat(status(NOW.minus(Duration.ofMinutes(5)).plusNanos(1)).status()).isEqualTo(Status.LIVE);
		assertThat(status(NOW.minus(Duration.ofMinutes(5)).minusNanos(1)).status()).isEqualTo(Status.DELAYED);
		assertThat(status(NOW).status()).isEqualTo(Status.LIVE);
		assertThat(status(NOW.plus(Duration.ofMinutes(1))).status()).isEqualTo(Status.LIVE);

		AisFeedStatus oneHour = status(NOW.minus(Duration.ofHours(1)));
		assertThat(oneHour.status()).isEqualTo(Status.DELAYED);
		assertThat(oneHour.lastReceivedAt()).isEqualTo(NOW.minus(Duration.ofHours(1)));
		assertThat(status(NOW.minus(Duration.ofHours(1)).minusNanos(1)).status()).isEqualTo(Status.OFFLINE);
	}

	@Test
	void keepsTheReportTimeWhenTheNewestStreamReportIsOffline() {
		Instant received = NOW.minus(Duration.ofHours(2));
		AisFeedStatus status = status(received);

		assertThat(status.status()).isEqualTo(Status.OFFLINE);
		assertThat(status.lastReceivedAt()).isEqualTo(received);
	}

	@Test
	void isOfflineWithNoReportTimeWhenNoStreamReportIsStored() {
		when(observations.findFirstBySourceOrderByIngestedAtDescIdDesc(AisPositionSource.AIS_STREAM))
				.thenReturn(Optional.empty());

		AisFeedStatus status = feeds.current();

		assertThat(status.status()).isEqualTo(Status.OFFLINE);
		assertThat(status.lastReceivedAt()).isNull();
		verify(observations).findFirstBySourceOrderByIngestedAtDescIdDesc(AisPositionSource.AIS_STREAM);
		verifyNoMoreInteractions(observations);
	}

	@Test
	void rejectsALiveFeedWithoutAReportTime() {
		assertThatThrownBy(() -> new AisFeedStatus(Status.LIVE, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AisFeedStatus(Status.DELAYED, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AisFeedStatus(null, NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private AisFeedStatus status(Instant received) {
		when(observations.findFirstBySourceOrderByIngestedAtDescIdDesc(AisPositionSource.AIS_STREAM))
				.thenReturn(Optional.of(observation(received)));
		AisFeedStatus status = feeds.current();
		assertThat(status.lastReceivedAt()).isEqualTo(received);
		return status;
	}

	private static VesselObservation observation(Instant received) {
		return new VesselObservation(new AisPosition("563123456", "PACIFIC HORIZON", new BigDecimal("1.264"),
				new BigDecimal("103.840"), new BigDecimal("12.4"), null, null, 1, true, null, received,
				AisPositionSource.AIS_STREAM));
	}
}
