-- CDG-247 validation statuses are explicit machine-readable rejection codes.
-- Keep the persisted schema aligned with the validator rather than truncating a
-- legitimate rejection and turning a safe FAILED assessment into a server error.
ALTER TABLE maritime_ai_assessments
    ALTER COLUMN validation_status TYPE VARCHAR(64);
