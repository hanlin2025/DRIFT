ALTER TABLE shipment_risk_transitions
    ADD COLUMN recipient_resolution_completed_at TIMESTAMPTZ;

-- These transitions predate synchronous recipient enrollment. Closing them prevents current
-- preferences or newly granted shipment visibility from creating historical alert deliveries.
UPDATE shipment_risk_transitions
SET recipient_resolution_completed_at = CURRENT_TIMESTAMP
WHERE recipient_resolution_completed_at IS NULL;
