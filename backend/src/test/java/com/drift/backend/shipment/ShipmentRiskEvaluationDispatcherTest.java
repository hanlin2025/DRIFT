package com.drift.backend.shipment;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class ShipmentRiskEvaluationDispatcherTest {
	@Test void evaluatesTheCommittedShipmentAndIsolatesFailure() {
		ShipmentRiskEvaluator evaluator = mock(ShipmentRiskEvaluator.class);
		ShipmentRiskEvaluationDispatcher dispatcher = new ShipmentRiskEvaluationDispatcher(evaluator);
		dispatcher.evaluateAfterCommit(new ShipmentRiskEvaluationRequestedEvent(41L));
		verify(evaluator).evaluate(41L);
		doThrow(new IllegalStateException()).when(evaluator).evaluate(42L);
		dispatcher.evaluateAfterCommit(new ShipmentRiskEvaluationRequestedEvent(42L));
		verify(evaluator).evaluate(42L);
	}
}
