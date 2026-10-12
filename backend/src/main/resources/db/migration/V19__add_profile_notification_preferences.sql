-- Preference only. This does not send shipment-risk email.
ALTER TABLE users
    ADD COLUMN risk_email_notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN minimum_risk_notification_level VARCHAR(16) NOT NULL DEFAULT 'HIGH';

ALTER TABLE users
    ADD CONSTRAINT users_minimum_risk_notification_level_check
    CHECK (minimum_risk_notification_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));
