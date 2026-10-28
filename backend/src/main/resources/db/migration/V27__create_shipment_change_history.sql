CREATE TABLE shipment_change_events (
    id BIGSERIAL PRIMARY KEY,
    shipment_id BIGINT NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    actor_user_id BIGINT NOT NULL REFERENCES users (id),
    change_type VARCHAR(32) NOT NULL,
    earlier_changes_unrecorded BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT shipment_change_events_change_type_check
        CHECK (change_type IN ('CREATED', 'UPDATED', 'IMPORTER_LINKED', 'ARCHIVED', 'RESTORED'))
);

CREATE INDEX shipment_change_events_shipment_recorded_at_idx
    ON shipment_change_events (shipment_id, recorded_at DESC, id DESC);

CREATE TABLE shipment_field_changes (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES shipment_change_events (id) ON DELETE CASCADE,
    field_name VARCHAR(64) NOT NULL,
    previous_value TEXT,
    new_value TEXT
);

CREATE INDEX shipment_field_changes_event_idx ON shipment_field_changes (event_id);

-- Shipments created before this migration get their creation as the first entry.
-- Any of them already updated since creation lost those earlier changes, so they are flagged.
INSERT INTO shipment_change_events (shipment_id, actor_user_id, change_type, earlier_changes_unrecorded, recorded_at)
SELECT id, created_by_user_id, 'CREATED', updated_at > created_at, created_at
FROM shipments;
