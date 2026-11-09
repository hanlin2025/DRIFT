package com.drift.backend.maritimeai;

import java.time.Instant;
import java.util.UUID;

/** Immutable identity, snapshot, evidence, and authorization binding for one scenario assessment. */
record MaritimeAiScenarioAssessmentContext(
		int schemaVersion,
		String scenarioId,
		String scenarioVersion,
		UUID sessionId,
		Long userId,
		UUID epochId,
		long revision,
		UUID snapshotId,
		String simulationCutoffUtc,
		String scenarioShipmentKey,
		String scenarioShipmentReference,
		String stage,
		Long shipmentId,
		Long viewerCompanyId,
		Long managingCompanyId,
		Long importerCompanyId,
		String evidenceHash,
		String bundleHash,
		String contextHash) {
	static final int CURRENT_VERSION = 1;

	boolean hasValidShape() {
		return schemaVersion == CURRENT_VERSION
				&& present(scenarioId) && present(scenarioVersion) && sessionId != null && positive(userId)
				&& epochId != null && revision >= 0 && snapshotId != null && canonicalInstant(simulationCutoffUtc)
				&& ("A".equals(scenarioShipmentKey) || "B".equals(scenarioShipmentKey))
				&& present(scenarioShipmentReference) && present(stage) && positive(shipmentId)
				&& positive(viewerCompanyId) && positive(managingCompanyId)
				&& (importerCompanyId == null || positive(importerCompanyId))
				&& sha256(evidenceHash) && sha256(bundleHash) && sha256(contextHash);
	}

	HashMaterial hashMaterial() {
		return new HashMaterial(schemaVersion, scenarioId, scenarioVersion, sessionId, userId, epochId, revision,
				snapshotId, simulationCutoffUtc, scenarioShipmentKey, scenarioShipmentReference, stage, shipmentId,
				viewerCompanyId, managingCompanyId, importerCompanyId, evidenceHash, bundleHash);
	}

	private static boolean present(String value) { return value != null && !value.isBlank(); }
	private static boolean positive(Long value) { return value != null && value > 0; }
	private static boolean sha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
	private static boolean canonicalInstant(String value) {
		if (!present(value)) return false;
		try { return Instant.parse(value).toString().equals(value); }
		catch (RuntimeException exception) { return false; }
	}

	record HashMaterial(
			int schemaVersion,
			String scenarioId,
			String scenarioVersion,
			UUID sessionId,
			Long userId,
			UUID epochId,
			long revision,
			UUID snapshotId,
			String simulationCutoffUtc,
			String scenarioShipmentKey,
			String scenarioShipmentReference,
			String stage,
			Long shipmentId,
			Long viewerCompanyId,
			Long managingCompanyId,
			Long importerCompanyId,
			String evidenceHash,
			String bundleHash) { }
}
