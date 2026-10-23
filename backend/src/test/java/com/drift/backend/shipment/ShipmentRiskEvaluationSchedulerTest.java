package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class ShipmentRiskEvaluationSchedulerTest {
	@Test void defaultsToDisabledFifteenMinutePollingAndAllowsOverrides() {
		var properties = new ShipmentRiskAlertEvaluationProperties();
		assertThat(properties.isEnabled()).isFalse();
		assertThat(properties.getInterval()).isEqualTo(Duration.ofMinutes(15));
		properties.setInterval(Duration.ofMinutes(2));
		assertThat(properties.getInterval()).isEqualTo(Duration.ofMinutes(2));
	}

	@Test void staysDisabledByDefault() {
		var repository = mock(ShipmentRepository.class); var evaluator = mock(ShipmentRiskEvaluator.class);
		var activation = mock(ShipmentRiskAlertActivationStore.class); var properties = new ShipmentRiskAlertEvaluationProperties();
		new ShipmentRiskEvaluationScheduler(repository, evaluator, activation, properties).sweep();
		verifyNoInteractions(repository, evaluator, activation);
	}

	@Test void pagesActiveShipmentsAndIsolatesEvaluatorFailures() {
		var repository = mock(ShipmentRepository.class); var evaluator = mock(ShipmentRiskEvaluator.class);
		var activation = mock(ShipmentRiskAlertActivationStore.class); var properties = new ShipmentRiskAlertEvaluationProperties(); properties.setEnabled(true); properties.setBatchSize(2);
		var gate = mock(ShipmentRiskAlertActivation.class); when(gate.isActive()).thenReturn(true); when(activation.lockForRiskInput()).thenReturn(gate);
		when(repository.findActiveIdsAfter(eq(0L), any(Pageable.class))).thenReturn(List.of(1L, 2L));
		when(repository.findActiveIdsAfter(eq(2L), any(Pageable.class))).thenReturn(List.of(3L));
		when(repository.findActiveIdsAfter(eq(3L), any(Pageable.class))).thenReturn(List.of());
		doThrow(new IllegalStateException()).when(evaluator).evaluate(2L);
		new ShipmentRiskEvaluationScheduler(repository, evaluator, activation, properties).sweep();
		verify(evaluator).evaluate(1L); verify(evaluator).evaluate(2L); verify(evaluator).evaluate(3L);
	}
}
