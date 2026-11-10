ALTER TABLE companies
    ADD COLUMN created_by_user_id BIGINT REFERENCES users (id);
