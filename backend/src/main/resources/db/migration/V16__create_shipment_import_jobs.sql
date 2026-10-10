CREATE TABLE shipment_import_jobs (
	id BIGSERIAL PRIMARY KEY,
	managing_company_id BIGINT NOT NULL REFERENCES companies(id),
	requested_by_user_id BIGINT NOT NULL REFERENCES users(id),
	status VARCHAR(20) NOT NULL,
	original_filename VARCHAR(255) NOT NULL,
	file_size_bytes BIGINT NOT NULL,
	content_type VARCHAR(255),
	source_csv BYTEA,
	total_rows INTEGER NOT NULL DEFAULT 0,
	processed_rows INTEGER NOT NULL DEFAULT 0,
	imported_count INTEGER NOT NULL DEFAULT 0,
	failed_count INTEGER NOT NULL DEFAULT 0,
	failure_message TEXT,
	created_at TIMESTAMPTZ NOT NULL,
	started_at TIMESTAMPTZ,
	completed_at TIMESTAMPTZ,
	CONSTRAINT shipment_import_jobs_status_check
		CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
	CONSTRAINT shipment_import_jobs_file_size_check
		CHECK (file_size_bytes >= 0)
);

CREATE INDEX shipment_import_jobs_company_created_idx
	ON shipment_import_jobs (managing_company_id, created_at DESC);

CREATE TABLE shipment_import_job_errors (
	id BIGSERIAL PRIMARY KEY,
	shipment_import_job_id BIGINT NOT NULL REFERENCES shipment_import_jobs(id) ON DELETE CASCADE,
	row_number BIGINT NOT NULL,
	column_name VARCHAR(255),
	error_code VARCHAR(100) NOT NULL,
	message TEXT NOT NULL
);

CREATE INDEX shipment_import_job_errors_job_row_idx
	ON shipment_import_job_errors (shipment_import_job_id, row_number, id);
