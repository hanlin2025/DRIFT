package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.ais.capture.RecordedAisCaptureReader;
import com.drift.backend.ais.capture.VerifiedAisCapture;
import com.drift.backend.ais.feed.AisFeedStatus;
import com.drift.backend.ais.feed.AisFeedStatusService;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.ais.position.StoredAisPosition;
import com.drift.backend.shipment.exception.InvalidShipmentRequestException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

@ExtendWith(MockitoExtension.class)
class VesselTrackServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
	private static final AuthenticatedUser USER = new AuthenticatedUser(7L, "forwarder@example.com",
			Role.FREIGHT_FORWARDER, Instant.parse("2026-10-13T13:00:00Z"));

	@Mock private ShipmentService shipments;
	@Mock private LatestAisPositions positions;
	@Mock private RecordedAisCaptureReader captures;
	@Mock private AisFeedStatusService feedStatus;

	private VesselTrackService service;

	@BeforeEach
	void setUp() {
		lenient().when(feedStatus.current()).thenReturn(new AisFeedStatus(AisFeedStatus.Status.OFFLINE, null));
		service = new VesselTrackService(shipments, positions, captures, feedStatus, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void readsTheMotherVesselOverTheLast24HoursByDefaultInTimeOrder() {
		when(shipments.tracking(USER, 4L)).thenReturn(tracking(latest("563001234"), null));
		when(positions.history("563001234", NOW.minusSeconds(24 * 3600), NOW, VesselTrackService.MAX_POINTS + 1))
				.thenReturn(List.of(fix(2, "2026-10-13T11:00:00Z", "2.0"), fix(1, "2026-10-13T09:00:00Z", "12.0")));

		VesselTrackResponse track = service.history(USER, 4L, null, null);

		assertThat(track.vessel()).isEqualTo(TrackVessel.MOTHER);
		assertThat(track.vesselName()).isEqualTo("MV Pacific Horizon");
		assertThat(track.mmsi()).isEqualTo("563001234");
		assertThat(track.windowHours()).isEqualTo(24);
		assertThat(track.from()).isEqualTo(Instant.parse("2026-10-12T12:00:00Z"));
		assertThat(track.to()).isEqualTo(NOW);
		assertThat(track.slowdownThresholdKnots()).isEqualByComparingTo("3");
		assertThat(track.truncated()).isFalse();
		assertThat(track.points()).extracting(TrackPoint::ingestedAt)
				.containsExactly(Instant.parse("2026-10-13T09:00:00Z"), Instant.parse("2026-10-13T11:00:00Z"));
		assertThat(track.points()).extracting(TrackPoint::id).containsExactly(1L, 2L);
	}

	@Test
	void keepsBothObservationsThatShareATimestampInIdOrder() {
		when(shipments.tracking(USER, 4L)).thenReturn(tracking(latest("563001234"), null));
		when(positions.history(anyString(), any(), any(), anyInt())).thenReturn(List.of(
				fix(12, "2026-10-13T11:00:00Z", "6.2"),
				fix(11, "2026-10-13T11:00:00Z", "1.5"),
				fix(10, "2026-10-13T10:00:00Z", "9.0")));

		VesselTrackResponse track = service.history(USER, 4L, null, null);

		assertThat(track.points()).extracting(TrackPoint::id).containsExactly(10L, 11L, 12L);
		assertThat(track.points()).extracting(point -> point.speedOverGroundKnots().toPlainString())
				.containsExactly("9.0", "1.5", "6.2");
	}

	@Test
	void readsTheFeederOverAPresetWindow() {
		when(shipments.tracking(USER, 4L)).thenReturn(tracking(latest("563001234"), latest("533000111")));
		when(positions.history("533000111", NOW.minusSeconds(6 * 3600), NOW, VesselTrackService.MAX_POINTS + 1))
				.thenReturn(List.of());

		VesselTrackResponse track = service.history(USER, 4L, "feeder", " 6 ");

		assertThat(track.vessel()).isEqualTo(TrackVessel.FEEDER);
		assertThat(track.vesselName()).isEqualTo("MV Strait Runner");
		assertThat(track.windowHours()).isEqualTo(6);
		assertThat(track.points()).isEmpty();
	}

	@Test
	void capsThePointsAndKeepsTheNewest() {
		List<StoredAisPosition> newestFirst = new ArrayList<>();
		for (int i = 0; i <= VesselTrackService.MAX_POINTS; i++) {
			newestFirst.add(fix(10_000 - i, NOW.minusSeconds(60L * (i + 1)).toString(), "10"));
		}
		when(shipments.tracking(USER, 4L)).thenReturn(tracking(latest("563001234"), null));
		when(positions.history(anyString(), any(), any(), anyInt())).thenReturn(newestFirst);

		VesselTrackResponse track = service.history(USER, 4L, "MOTHER", "72");

		assertThat(track.truncated()).isTrue();
		assertThat(track.points()).hasSize(VesselTrackService.MAX_POINTS);
		assertThat(track.points().getLast().ingestedAt()).isEqualTo(NOW.minusSeconds(60));
		assertThat(track.points().getFirst().ingestedAt()).isEqualTo(NOW.minusSeconds(60L * VesselTrackService.MAX_POINTS));
	}

	@Test
	void returnsNoPointsForAVesselWithoutAReport() {
		when(shipments.tracking(USER, 4L)).thenReturn(tracking(null, null));

		VesselTrackResponse track = service.history(USER, 4L, "MOTHER", "24");

		assertThat(track.mmsi()).isNull();
		assertThat(track.points()).isEmpty();
		verifyNoInteractions(positions);
	}

	@Test
	void rejectsAnUnknownVesselOrWindowBeforeReadingTheShipment() {
		assertThatThrownBy(() -> service.history(USER, 4L, "TUG", null))
				.isInstanceOfSatisfying(InvalidShipmentRequestException.class, ex ->
						assertThat(ex.getErrors()).containsEntry("vessel", VesselTrackService.INVALID_VESSEL));
		for (String hours : List.of("5", "0", "-6", "abc", "6.5", "99999999999")) {
			assertThatThrownBy(() -> service.history(USER, 4L, null, hours))
					.isInstanceOfSatisfying(InvalidShipmentRequestException.class, ex ->
							assertThat(ex.getErrors()).containsEntry("hours", VesselTrackService.INVALID_WINDOW));
		}
		verifyNoInteractions(shipments, positions);
	}

	@Test
	void usesTheVisibilityCheckOfTracking() {
		when(shipments.tracking(USER, 4L)).thenThrow(new ShipmentNotFoundException());

		assertThatThrownBy(() -> service.history(USER, 4L, null, null))
				.isInstanceOf(ShipmentNotFoundException.class);
		verify(shipments).tracking(USER, 4L);
	}

	@Test
	void readsOnlySeededObservationsAtOrBeforeTheCutoffAndKeepsStableTies() {
		Instant cutoff = NOW;
		when(shipments.trackIdentity(USER, 4L)).thenReturn(identity());
		when(positions.hasSourceInWindow("563001234", cutoff.minus(Duration.ofHours(72)), cutoff, AisPositionSource.AIS_STREAM))
				.thenReturn(false);
		when(positions.history("563001234", cutoff.minus(Duration.ofHours(72)), cutoff,
				VesselTrackService.MAX_POINTS + 1, AisPositionSource.SEEDED)).thenReturn(List.of(
				fix(14, cutoff.plusSeconds(1).toString(), "7.0"),
				fix(13, cutoff.toString(), "6.3"), fix(12, cutoff.toString(), "6.2"),
				fix(11, cutoff.minusSeconds(1).toString(), "6.1"),
				fix(9, cutoff.minus(Duration.ofHours(6)).toString(), "4.1"),
				fix(10, cutoff.minus(Duration.ofHours(7)).toString(), "4.0")));

		VesselTrackResponse result = service.history(USER, 4L, "MOTHER", "6", cutoff.toString(), null);

		assertThat(result.cutoff()).isEqualTo(cutoff);
		assertThat(result.context()).isEqualTo(TrackContext.SEEDED);
		assertThat(result.points()).extracting(TrackPoint::id).containsExactly(11L, 12L, 13L);
		assertThat(result.points()).allSatisfy(point -> assertThat(!point.ingestedAt().isAfter(cutoff)).isTrue());
		assertThat(result.points().get(1).speedOverGroundKnots()).isEqualByComparingTo("6.2");
		assertThat(result.lastKnownPosition().id()).isEqualTo(13L);
		verifyNoInteractions(captures);
	}

	@Test
	void retrievesVerifiedAuthenticCaptureOnlyAfterShipmentIdentityIsResolved() {
		Instant cutoff = NOW;
		TrackPoint point = trackPoint(31, cutoff.minusSeconds(5), AisPositionSource.AIS_STREAM);
		when(shipments.trackIdentity(USER, 4L)).thenReturn(identity());
		when(captures.read("capture-uuid", TrackVessel.MOTHER, "9387421", "563001234", cutoff))
				.thenReturn(new VerifiedAisCapture("capture-uuid", "MOTHER", "MV Pacific Horizon", "563001234",
						cutoff.minus(Duration.ofHours(1)), cutoff, java.time.LocalDate.of(2026, 10, 30), List.of(point)));

		VesselTrackResponse result = service.history(USER, 4L, "MOTHER", "6", cutoff.toString(), "capture-uuid");

		assertThat(result.context()).isEqualTo(TrackContext.RECORDED);
		assertThat(result.captureId()).isEqualTo("capture-uuid");
		assertThat(result.points()).containsExactly(point);
		verify(shipments).trackIdentity(USER, 4L);
		verifyNoInteractions(positions);
	}

	@Test
	void refusesDatabaseAisStreamPlaybackWithoutAnApprovedCaptureId() {
		Instant cutoff = NOW;
		when(shipments.trackIdentity(USER, 4L)).thenReturn(identity());
		when(positions.hasSourceInWindow("563001234", cutoff.minus(Duration.ofHours(72)), cutoff, AisPositionSource.AIS_STREAM))
				.thenReturn(true);

		assertThatThrownBy(() -> service.history(USER, 4L, "MOTHER", "24", cutoff.toString(), null))
				.isInstanceOfSatisfying(InvalidShipmentRequestException.class, ex ->
						assertThat(ex.getErrors()).containsEntry("captureId", VesselTrackService.AUTHENTIC_CAPTURE_REQUIRED));
		verify(shipments).trackIdentity(USER, 4L);
		verifyNoInteractions(captures);
	}

	@Test
	void checksShipmentVisibilityBeforeOpeningARequestedCapture() {
		Instant cutoff = NOW;
		when(shipments.trackIdentity(USER, 4L)).thenThrow(new ShipmentNotFoundException());

		assertThatThrownBy(() -> service.history(USER, 4L, "MOTHER", "24", cutoff.toString(), "capture-uuid"))
				.isInstanceOf(ShipmentNotFoundException.class);
		verifyNoInteractions(captures, positions);
	}

	private static ShipmentTrackingResponse tracking(VesselPosition mother, VesselPosition feeder) {
		return new ShipmentTrackingResponse("MV Pacific Horizon", mother, null, "MV Strait Runner", feeder, null);
	}

	private static ShipmentTrackIdentity identity() {
		return new ShipmentTrackIdentity("MV Pacific Horizon", "9387421", "563001234",
				"MV Strait Runner", "9411889", "533000111");
	}

	private static VesselPosition latest(String mmsi) {
		return new VesselPosition(mmsi, "PACIFIC HORIZON", new BigDecimal("1.264"), new BigDecimal("103.82"),
				new BigDecimal("12.4"), new BigDecimal("175.0"), 176, NOW, AisPositionSource.AIS_STREAM);
	}

	private static StoredAisPosition fix(long id, String at, String speed) {
		return new StoredAisPosition(id, new AisPosition("563001234", "PACIFIC HORIZON", new BigDecimal("1.264"), new BigDecimal("103.82"),
				new BigDecimal(speed), new BigDecimal("175.0"), 176, 0, true, 12, Instant.parse(at),
				AisPositionSource.SEEDED));
	}

	private static TrackPoint trackPoint(long id, Instant at, AisPositionSource source) {
		return new TrackPoint(id, new BigDecimal("1.264"), new BigDecimal("103.82"), new BigDecimal("8.0"),
				new BigDecimal("175.0"), 176, at, source, true);
	}
}
