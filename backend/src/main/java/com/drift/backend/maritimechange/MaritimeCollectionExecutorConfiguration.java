package com.drift.backend.maritimechange;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps slow collector subprocesses off Spring's shared scheduled-task thread. */
@Configuration
@ConditionalOnProperty(name = "app.maritime-change.collection.enabled", havingValue = "true")
class MaritimeCollectionExecutorConfiguration {
	@Bean(destroyMethod = "shutdown")
	ExecutorService maritimeCollectionExecutor() {
		return Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "drift-maritime-collector");
			thread.setDaemon(true);
			return thread;
		});
	}
}
