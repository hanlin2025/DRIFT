package com.drift.backend.maritimereassessment;

import org.springframework.stereotype.Component;

/** Default provider. It has no endpoint and the orchestrator does not call it. */
@Component
class NoNetworkMaritimeReassessmentProvider implements MaritimeReassessmentProvider {

	private boolean called;

	@Override
	public boolean opensNetwork() {
		return false;
	}

	@Override
	public MaritimeProviderReply assess(MaritimeProviderRequest request) {
		called = true;
		throw new IllegalStateException("No network provider is configured");
	}

	boolean wasCalled() {
		return called;
	}
}
