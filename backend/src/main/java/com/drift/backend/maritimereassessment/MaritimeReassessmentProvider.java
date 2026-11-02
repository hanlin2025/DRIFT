package com.drift.backend.maritimereassessment;

/**
 * The assessment provider. The merged build has no CDG-247 client, so the default implementation does not open a network connection.
 */
public interface MaritimeReassessmentProvider {

	/** False when calling {@link #assess} would be required to reach a network. The orchestrator does not call it then. */
	boolean opensNetwork();

	MaritimeProviderReply assess(MaritimeProviderRequest request);
}
