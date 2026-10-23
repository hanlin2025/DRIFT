package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ShipmentRiskAlertActivationConcurrencyIntegrationTests {
	@Autowired ShipmentRiskAlertActivationService activation;
	@Autowired ShipmentRiskAlertActivationStore activationStore;

	@Test
	void concurrentFirstActivationAdvancesToGenerationOneExactlyOnce() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<ShipmentRiskAlertActivationResult>> results = List.of(
					executor.submit(() -> activateWhenReleased(ready, start)),
					executor.submit(() -> activateWhenReleased(ready, start)));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(results.stream().map(this::get)).containsExactlyInAnyOrder(
					ShipmentRiskAlertActivationResult.ACTIVATED, ShipmentRiskAlertActivationResult.ALREADY_ACTIVE);
			ShipmentRiskAlertActivation state = activationStore.lockForRiskInput();
			assertThat(state.getGeneration()).isEqualTo(1);
			assertThat(state.getActivatedAt()).isNotNull();
		} finally {
			executor.shutdownNow();
		}
	}

	private ShipmentRiskAlertActivationResult activateWhenReleased(CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent activation did not start");
		return activation.activate();
	}

	private ShipmentRiskAlertActivationResult get(Future<ShipmentRiskAlertActivationResult> result) {
		try {
			return result.get(30, TimeUnit.SECONDS);
		} catch (Exception ex) {
			throw new AssertionError("Concurrent activation failed", ex);
		}
	}
}
