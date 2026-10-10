CREATE TABLE forwarder_importer_relationships (
	id BIGSERIAL PRIMARY KEY,
	forwarder_company_id BIGINT NOT NULL REFERENCES companies(id),
	importer_company_id BIGINT NOT NULL REFERENCES companies(id),
	active BOOLEAN NOT NULL DEFAULT TRUE,
	created_at TIMESTAMPTZ NOT NULL,
	created_by_user_id BIGINT NOT NULL REFERENCES users(id),
	updated_at TIMESTAMPTZ NOT NULL,
	updated_by_user_id BIGINT NOT NULL REFERENCES users(id),
	CONSTRAINT forwarder_importer_relationships_different_companies
		CHECK (forwarder_company_id <> importer_company_id),
	CONSTRAINT forwarder_importer_relationships_unique_pair
		UNIQUE (forwarder_company_id, importer_company_id)
);

CREATE INDEX forwarder_importer_relationships_active_lookup_idx
	ON forwarder_importer_relationships (forwarder_company_id, importer_company_id, active);
