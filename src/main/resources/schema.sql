CREATE SEQUENCE IF NOT EXISTS claim_number_seq START 1;

CREATE TABLE IF NOT EXISTS claim (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    business_book VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    claim_number VARCHAR(32) NOT NULL,
    policy_id UUID NOT NULL,
    claimant_party_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    loss_date DATE NOT NULL,
    report_date DATE NOT NULL,
    cause_code VARCHAR(24),
    reserve_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    reserve_currency CHAR(3) NOT NULL DEFAULT 'USD',
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_claim_number UNIQUE (tenant_id, claim_number),
    CONSTRAINT ck_claim_book_direct CHECK (business_book = 'DIRECT'),
    CONSTRAINT ck_claim_report_date CHECK (report_date >= loss_date),
    CONSTRAINT ck_claim_reserve_non_negative CHECK (reserve_amount >= 0),
    CONSTRAINT ck_claim_currency CHECK (reserve_currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_claim_tenant_status ON claim (tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_claim_policy ON claim (tenant_id, policy_id);
