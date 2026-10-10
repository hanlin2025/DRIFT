package com.drift.backend.shipment.csvimport;

import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.drift.backend.config.ShipmentImportTaskConfiguration;

@Component
public class ShipmentImportJobDispatcher {

	private final TaskExecutor executor;
	private final ShipmentImportJobProcessor processor;

	public ShipmentImportJobDispatcher(
			@org.springframework.beans.factory.annotation.Qualifier(ShipmentImportTaskConfiguration.EXECUTOR_NAME) TaskExecutor executor,
			ShipmentImportJobProcessor processor) {
		this.executor = executor;
		this.processor = processor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void dispatchAfterCommit(ShipmentImportJobCreatedEvent event) {
		executor.execute(() -> processor.process(event.jobId()));
	}
}
