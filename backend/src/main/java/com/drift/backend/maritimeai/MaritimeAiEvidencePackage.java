package com.drift.backend.maritimeai;

import java.util.List;

import com.drift.backend.maritimecontext.MaritimeEvidence;

/** Deliberately bounded package: no database, filesystem, raw source body, or credential is exposed to a client. */
public record MaritimeAiEvidencePackage(String bundleVersion, String assessmentCutoffUtc, String shipmentAssociationConfidence,
		List<MaritimeEvidence> evidence) { }
