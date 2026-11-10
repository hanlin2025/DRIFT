package com.drift.backend.maritimechange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** In-process health for the bounded collector; failures never remove persisted observations. */
@Component
class MaritimeCollectionHealth {
	private final Map<String, Boolean> outcomes = new ConcurrentHashMap<>();

	void record(String source, boolean successful) {
		outcomes.put(source, successful);
	}

	String availability() {
		if (outcomes.isEmpty() || outcomes.values().stream().noneMatch(Boolean::booleanValue)) {
			return "UNAVAILABLE";
		}
		return outcomes.values().stream().allMatch(Boolean::booleanValue) ? "AVAILABLE" : "PARTIAL";
	}
}
