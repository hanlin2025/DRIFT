package com.drift.backend.ais.position;

/** A stored AIS observation with its {@code vessel_observations} id, which stays unique when timestamps tie. */
public record StoredAisPosition(long id, AisPosition position) {
}
