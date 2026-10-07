-- Run by the migration role (owns the table). docker-compose and the integration tests both run this file.

CREATE ROLE calc_app LOGIN PASSWORD 'calc_app';

CREATE TABLE ndi_calculation (
    tenant_id          text        NOT NULL,
    calculation_id     uuid        NOT NULL,
    calculator_version text        NOT NULL,
    idempotency_key    text,
    input              jsonb       NOT NULL,
    result             jsonb       NOT NULL,
    computed_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, calculation_id),
    UNIQUE (tenant_id, idempotency_key)
);

ALTER TABLE ndi_calculation ENABLE ROW LEVEL SECURITY;
ALTER TABLE ndi_calculation FORCE ROW LEVEL SECURITY;

-- A row is visible, and writable, only when it belongs to the tenant
-- the current transaction was opened for.
CREATE POLICY tenant_isolation ON ndi_calculation
    USING      (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- The service connects as calc_app: not the owner, no BYPASSRLS. Results are append-only.
GRANT SELECT, INSERT ON ndi_calculation TO calc_app;
