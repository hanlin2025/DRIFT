package com.drift.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ShipmentImportTaskConfiguration {

	public static final String EXECUTOR_NAME = "shipmentImportTaskExecutor";

	@Bean(name = EXECUTOR_NAME)
	public TaskExecutor shipmentImportTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(32);
		executor.setThreadNamePrefix("shipment-import-");
		executor.initialize();
		return executor;
	}
}
