CREATE TABLE maritime_snapshot_observations (
    id BIGSERIAL PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_identity VARCHAR(300) NOT NULL,
    content_fingerprint VARCHAR(64) NOT NULL,
    change_kind VARCHAR(16) NOT NULL,
    ordering VARCHAR(16) NOT NULL,
    forward_looking BOOLEAN NOT NULL,
    head BOOLEAN NOT NULL,
    provenance TEXT NOT NULL,
    source_event_at TEXT,
    observed_at TEXT,
    retrieved_at_utc TIMESTAMPTZ NOT NULL,
    source_mode VARCHAR(32) NOT NULL,
    freshness VARCHAR(32) NOT NULL,
    vessel_imo VARCHAR(7),
    location TEXT,
    effective_from TIMESTAMPTZ,
    effective_until TIMESTAMPTZ,
    previous_value TEXT,
    current_value TEXT,
    activity_compatibility VARCHAR(16),
    location_compatibility VARCHAR(16),
    time_compatibility VARCHAR(16),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT maritime_snapshot_observations_fingerprint_key
        UNIQUE (source_type, source_identity, content_fingerprint),
    CONSTRAINT maritime_snapshot_observations_source_type_check
        CHECK (source_type IN ('MPA_ETA_REVISION', 'MPA_OFFICIAL_NOTICE', 'NEA_WEATHER')),
    CONSTRAINT maritime_snapshot_observations_source_mode_check
        CHECK (source_mode IN ('LIVE', 'RECORDED_REPLAY', 'SYNTHETIC_DEMONSTRATION', 'HISTORICAL_BACKFILL')),
    CONSTRAINT maritime_snapshot_observations_freshness_check
        CHECK (freshness IN ('APPLICABLE', 'HISTORICAL', 'UNKNOWN')),
    CONSTRAINT maritime_snapshot_observations_kind_check
        CHECK (change_kind IN ('NEW', 'REVISED')),
    CONSTRAINT maritime_snapshot_observations_ordering_check
        CHECK (ordering IN ('FORWARD', 'OUT_OF_ORDER', 'BACKFILL')),
    CONSTRAINT maritime_snapshot_observations_activity_check
        CHECK (activity_compatibility IS NULL OR activity_compatibility IN ('COMPATIBLE', 'INCOMPATIBLE', 'UNKNOWN')),
    CONSTRAINT maritime_snapshot_observations_location_check
        CHECK (location_compatibility IS NULL OR location_compatibility IN ('COMPATIBLE', 'INCOMPATIBLE', 'UNKNOWN')),
    CONSTRAINT maritime_snapshot_observations_time_check
        CHECK (time_compatibility IS NULL OR time_compatibility IN ('COMPATIBLE', 'INCOMPATIBLE', 'UNKNOWN'))
);

CREATE INDEX maritime_snapshot_observations_identity_idx
    ON maritime_snapshot_observations (source_type, source_identity);

CREATE UNIQUE INDEX maritime_snapshot_observations_one_head_idx
    ON maritime_snapshot_observations (source_type, source_identity)
    WHERE head;
