CREATE INDEX maritime_snapshot_observations_recorded_at_id_idx
    ON maritime_snapshot_observations (recorded_at DESC, id DESC);
