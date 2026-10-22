-- H2-compatible schema for the Direct Claims component tests.
--
-- The production DDL is src/main/resources/schema.sql (PostgreSQL: jsonb
-- outbox payloads, partial indexes, text arrays). This copy keeps only the
-- tables the outbox integration tests exercise and uses TEXT for the JSON
-- columns. H2 runs in PostgreSQL compatibility mode.

CREATE SEQUENCE IF NOT EXISTS claim_number_seq START WITH 1;
CREATE SEQUENCE IF NOT EXISTS settlement_number_seq START WITH 1;
CREATE SEQUENCE IF NOT EXISTS claim_task_number_seq START WITH 1;
CREATE SEQUENCE IF NOT EXISTS claim_note_number_seq START WITH 1;

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
    claim_type VARCHAR(32) NOT NULL DEFAULT 'BODILY_INJURY',
    settlement_authority_amount NUMERIC(19, 4),
    settlement_authority_currency CHAR(3),
    litigation_flag BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_adjuster VARCHAR(128),
    assigned_team VARCHAR(64),
    assigned_at TIMESTAMP WITH TIME ZONE,
    assigned_by VARCHAR(128),
    reserve_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    reserve_currency CHAR(3) NOT NULL DEFAULT 'USD',
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_claim_number UNIQUE (tenant_id, claim_number),
    CONSTRAINT ck_claim_book_direct CHECK (business_book = 'DIRECT'),
    CONSTRAINT ck_claim_report_date CHECK (report_date >= loss_date),
    CONSTRAINT ck_claim_reserve_non_negative CHECK (reserve_amount >= 0)
);

ALTER TABLE claim ADD COLUMN IF NOT EXISTS assigned_adjuster VARCHAR(128);
ALTER TABLE claim ADD COLUMN IF NOT EXISTS assigned_team VARCHAR(64);
ALTER TABLE claim ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE claim ADD COLUMN IF NOT EXISTS assigned_by VARCHAR(128);

CREATE TABLE IF NOT EXISTS claim_reserve_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    component VARCHAR(16) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    effective_date DATE NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    reason_text TEXT,
    authority VARCHAR(120),
    sequence INTEGER NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_reserve_sequence UNIQUE (claim_id, component, sequence)
);

CREATE TABLE IF NOT EXISTS claim_coverage_decision (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    coverage_position VARCHAR(16) NOT NULL,
    basis TEXT NOT NULL,
    policy_provisions TEXT,
    allocation_method VARCHAR(24),
    allocation_pct NUMERIC(9, 6),
    decision_date DATE NOT NULL,
    reviewer_party_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'FINAL',
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS claim_settlement (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    settlement_number VARCHAR(32) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    structure VARCHAR(24) NOT NULL,
    release_scope VARCHAR(24),
    approval_authority VARCHAR(120),
    payment_instructions TEXT,
    release_document_id UUID,
    status VARCHAR(16) NOT NULL,
    authorized_by UUID,
    authorized_at TIMESTAMP WITH TIME ZONE,
    executed_at TIMESTAMP WITH TIME ZONE,
    payment_id UUID,
    payment_linked_at TIMESTAMP WITH TIME ZONE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_settlement_number UNIQUE (tenant_id, settlement_number)
);

ALTER TABLE claim_settlement ADD COLUMN IF NOT EXISTS payment_id UUID;
ALTER TABLE claim_settlement ADD COLUMN IF NOT EXISTS payment_linked_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE IF NOT EXISTS claim_lien (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    lien_type VARCHAR(24) NOT NULL,
    lienholder VARCHAR(160) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    resolution_type VARCHAR(16),
    resolved_at TIMESTAMP WITH TIME ZONE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS claim_allocation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    policy_id UUID NOT NULL,
    limit_id UUID,
    policy_year INTEGER NOT NULL,
    layer VARCHAR(32) NOT NULL,
    allocation_pct NUMERIC(9, 6) NOT NULL,
    allocated_amount NUMERIC(19, 4) NOT NULL,
    erosion_status VARCHAR(16) NOT NULL DEFAULT 'NOT_POSTED',
    erosion_message TEXT,
    erosion_posted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_claim_allocation_pct CHECK (allocation_pct > 0 AND allocation_pct <= 1),
    CONSTRAINT ck_claim_allocation_amount CHECK (allocated_amount >= 0),
    CONSTRAINT ck_claim_allocation_erosion_status
        CHECK (erosion_status IN ('NOT_POSTED', 'POSTED', 'FAILED')),
    CONSTRAINT ck_claim_allocation_erosion_posted
        CHECK ((erosion_status = 'POSTED' AND erosion_posted_at IS NOT NULL)
            OR (erosion_status <> 'POSTED' AND erosion_posted_at IS NULL))
);

-- The erosion columns are additive for an existing H2 database; the status
-- default keeps existing allocations at NOT_POSTED.
ALTER TABLE claim_allocation ADD COLUMN IF NOT EXISTS limit_id UUID;
ALTER TABLE claim_allocation ADD COLUMN IF NOT EXISTS erosion_status VARCHAR(16) NOT NULL DEFAULT 'NOT_POSTED';
ALTER TABLE claim_allocation ADD COLUMN IF NOT EXISTS erosion_message TEXT;
ALTER TABLE claim_allocation ADD COLUMN IF NOT EXISTS erosion_posted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE claim_allocation DROP CONSTRAINT IF EXISTS ck_claim_allocation_erosion_status;
ALTER TABLE claim_allocation ADD CONSTRAINT ck_claim_allocation_erosion_status
    CHECK (erosion_status IN ('NOT_POSTED', 'POSTED', 'FAILED'));
ALTER TABLE claim_allocation DROP CONSTRAINT IF EXISTS ck_claim_allocation_erosion_posted;
ALTER TABLE claim_allocation ADD CONSTRAINT ck_claim_allocation_erosion_posted
    CHECK ((erosion_status = 'POSTED' AND erosion_posted_at IS NOT NULL)
        OR (erosion_status <> 'POSTED' AND erosion_posted_at IS NULL));

CREATE INDEX IF NOT EXISTS ix_claim_allocation_claim
    ON claim_allocation (tenant_id, claim_id);

CREATE TABLE IF NOT EXISTS claim_task (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    task_number VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    due_date DATE NOT NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assigned_to VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_claim_task_number UNIQUE (tenant_id, task_number),
    CONSTRAINT ck_claim_task_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_claim_task_status CHECK (status IN ('OPEN', 'COMPLETE', 'CANCELLED')),
    CONSTRAINT ck_claim_task_completed CHECK (
        (status = 'COMPLETE' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETE' AND completed_at IS NULL))
);

CREATE INDEX IF NOT EXISTS ix_claim_task_status_due
    ON claim_task (tenant_id, claim_id, status, due_date);
CREATE INDEX IF NOT EXISTS ix_claim_task_created
    ON claim_task (tenant_id, claim_id, created_at DESC);

CREATE TABLE IF NOT EXISTS claim_note (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    note_number VARCHAR(32) NOT NULL,
    note_type VARCHAR(24) NOT NULL,
    body TEXT NOT NULL,
    author VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_claim_note_number UNIQUE (tenant_id, note_number),
    CONSTRAINT ck_claim_note_type CHECK (note_type IN (
        'DIARY', 'COVERAGE', 'RESERVE', 'LITIGATION', 'SETTLEMENT', 'GENERAL'))
);

CREATE INDEX IF NOT EXISTS ix_claim_note_created
    ON claim_note (tenant_id, claim_id, created_at DESC);

-- H2-compatible copy of the command idempotency table. The production column is
-- jsonb; here it is TEXT so the plain bound parameter can be read back as JSON.
-- A command reserves the key first and stores the response on completion, so
-- both response columns are nullable for the reservation window.
CREATE TABLE IF NOT EXISTS claim_command_idempotency (
    tenant_id UUID NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INTEGER,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_claim_command_idempotency
        PRIMARY KEY (tenant_id, idempotency_key, command_type)
);

CREATE INDEX IF NOT EXISTS ix_claim_command_idempotency_created
    ON claim_command_idempotency (tenant_id, command_type, created_at DESC);

-- H2-compatible copy of the PostgreSQL outbox table. JSON payloads are TEXT
-- here; production DDL uses jsonb.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    headers TEXT NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
