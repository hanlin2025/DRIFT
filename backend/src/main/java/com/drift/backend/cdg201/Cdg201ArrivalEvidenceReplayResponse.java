package com.drift.backend.cdg201;

/** Stable JSON envelope: evidence can be null without turning a valid 200 into an empty body. */
public record Cdg201ArrivalEvidenceReplayResponse(boolean readOnlyReplay, Cdg201ArrivalEvidence evidence) {
}
