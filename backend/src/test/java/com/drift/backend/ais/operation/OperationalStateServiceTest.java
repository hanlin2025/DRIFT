package com.drift.backend.ais.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class OperationalStateServiceTest {

	private static final BigDecimal SINGAPORE_LATITUDE = new BigDecimal("1.264");
	private static final BigDecimal SINGAPORE_LONGITUDE = new BigDecimal("103.840");
	private static final BigDecimal PELEPAS_LATITUDE = new BigDecimal("1.362");
	private static final BigDecimal PELEPAS_LONGITUDE = new BigDecimal("103.549");

	private final OperationalStateService states = new OperationalStateService();

	@Test
	void mapsDefinedNavigationalStatusesToReadableLabels() {
		assertThat(label(0)).isEqualTo("Under way");
		assertThat(label(1)).isEqualTo("At anchor");
		assertThat(label(2)).isEqualTo("Not under command");
		assertThat(label(3)).isEqualTo("Restricted manoeuvrability");
		assertThat(label(4)).isEqualTo("Constrained by draught");
		assertThat(label(5)).isEqualTo("Moored");
		assertThat(label(6)).isEqualTo("Aground");
		assertThat(label(7)).isEqualTo("Engaged in fishing");
		assertThat(label(8)).isEqualTo("Under way sailing");
		assertThat(label(11)).isEqualTo("Towing astern");
		assertThat(label(12)).isEqualTo("Towing alongside");
	}

	@Test
	void treatsMissingUndefinedAndUnrecognisedStatusesAsUnknown() {
		assertThat(label(null)).isEqualTo("Unknown");
		assertThat(label(15)).isEqualTo("Unknown");
		assertThat(label(9)).isEqualTo("Unknown");
		assertThat(label(10)).isEqualTo("Unknown");
		assertThat(label(13)).isEqualTo("Unknown");
		assertThat(label(14)).isEqualTo("Unknown");
		assertThat(label(-1)).isEqualTo("Unknown");
		assertThat(label(99)).isEqualTo("Unknown");
	}

	@Test
	void marksAStoppedStatusAsUnreliableWhenSpeedIsAboveOneKnot() {
		assertThat(states.derive(5, new BigDecimal("1.0"), null, null, null).unreliable()).isFalse();
		assertThat(states.derive(5, new BigDecimal("1.1"), null, null, null).unreliable()).isTrue();
		assertThat(states.derive(1, new BigDecimal("3"), null, null, null).unreliable()).isTrue();
		assertThat(states.derive(6, new BigDecimal("2.5"), null, null, null).unreliable()).isTrue();
		assertThat(states.derive(5, new BigDecimal("0.4"), null, null, null).unreliable()).isFalse();
	}

	@Test
	void leavesTheStateReliableWhenSpeedIsMissingOrTheVesselIsNotReportedStopped() {
		assertThat(states.derive(5, null, null, null, null).unreliable()).isFalse();
		assertThat(states.derive(0, new BigDecimal("18"), null, null, null).unreliable()).isFalse();
		assertThat(states.derive(0, new BigDecimal("0"), null, null, null).unreliable()).isFalse();
		assertThat(states.derive(8, new BigDecimal("12.4"), null, null, null).unreliable()).isFalse();
		assertThat(states.derive(2, new BigDecimal("6"), null, null, null).unreliable()).isFalse();
		assertThat(states.derive(null, new BigDecimal("10"), null, null, null).unreliable()).isFalse();
	}

	@Test
	void describesTheStateRelativeToTheTransshipmentPort() {
		OperationalState anchored = states.derive(1, new BigDecimal("0.2"), SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE,
				"Singapore");
		OperationalState sailing = states.derive(0, new BigDecimal("12.4"), new BigDecimal("10.0"),
				new BigDecimal("110.0"), "Singapore");

		assertThat(anchored.label()).isEqualTo("At anchor");
		assertThat(anchored.description()).isEqualTo("At anchor near Singapore");
		assertThat(anchored.unreliable()).isFalse();
		assertThat(sailing.label()).isEqualTo("Under way");
		assertThat(sailing.description()).isEqualTo("Under way, far from port");
	}

	@Test
	void keepsAVesselInsideThePortRadiusNearAndAVesselBeyondItFar() {
		OperationalState near = states.derive(5, new BigDecimal("0.1"), new BigDecimal("1.664"), SINGAPORE_LONGITUDE,
				"Singapore");
		OperationalState far = states.derive(5, new BigDecimal("0.1"), new BigDecimal("1.698"), SINGAPORE_LONGITUDE,
				"Singapore");

		assertThat(near.description()).isEqualTo("Moored near Singapore");
		assertThat(far.description()).isEqualTo("Moored, far from port");
	}

	@Test
	void recognisesThePortNameInAnyCaseAndUsesAnotherKnownPort() {
		assertThat(states.derive(1, new BigDecimal("0.2"), SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "  singapore, SG ")
				.description()).isEqualTo("At anchor near Singapore");
		assertThat(states.derive(5, new BigDecimal("0.0"), PELEPAS_LATITUDE, PELEPAS_LONGITUDE, "Tanjung Pelepas")
				.description()).isEqualTo("Moored near Tanjung Pelepas");
	}

	@Test
	void omitsPortContextWhenThePortOrPositionIsUnknown() {
		assertThat(states.derive(5, new BigDecimal("0.2"), SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "Jakarta")
				.description()).isEqualTo("Moored");
		assertThat(states.derive(5, new BigDecimal("0.2"), SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "  ")
				.description()).isEqualTo("Moored");
		assertThat(states.derive(0, new BigDecimal("12.4"), null, SINGAPORE_LONGITUDE, "Singapore").description())
				.isEqualTo("Under way");
		assertThat(states.derive(0, new BigDecimal("12.4"), SINGAPORE_LATITUDE, null, "Singapore").description())
				.isEqualTo("Under way");
		assertThat(states.derive(15, null, null, null, "Singapore").description()).isEqualTo("Unknown");
	}

	@Test
	void keepsTheUnreliableMarkSeparateFromThePortDescription() {
		OperationalState state = states.derive(5, new BigDecimal("4.0"), SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE,
				"Singapore");

		assertThat(state.label()).isEqualTo("Moored");
		assertThat(state.description()).isEqualTo("Moored near Singapore");
		assertThat(state.unreliable()).isTrue();
	}

	@Test
	void derivesEachReportOnItsOwn() {
		OperationalState earlier = states.derive(0, new BigDecimal("14"), new BigDecimal("8.0"),
				new BigDecimal("108.0"), "Singapore");
		OperationalState later = states.derive(1, new BigDecimal("0.3"), SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE,
				"Singapore");

		assertThat(earlier.description()).isEqualTo("Under way, far from port");
		assertThat(later.description()).isEqualTo("At anchor near Singapore");
	}

	@Test
	void rejectsAContradictoryState() {
		assertThatThrownBy(() -> new OperationalState(" ", "Moored", false))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OperationalState("Moored", " ", false))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OperationalState("Moored", "At anchor near Singapore", false))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private String label(Integer navigationalStatus) {
		return states.derive(navigationalStatus, null, null, null, null).label();
	}
}
