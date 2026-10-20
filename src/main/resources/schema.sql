-- Direct Claims expert schema.
--
-- Every statement is idempotent: the service runs this file on every startup
-- (spring.sql.init.mode=always) and the same DDL is safe to apply to an
-- existing database. New columns are additive; constraints are dropped and
-- re-added so a repeat run is a no-op.

CREATE SEQUENCE IF NOT EXISTS claim_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS settlement_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS claim_task_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS claim_note_number_seq START 1;

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
    settlement_authority_amount NUMERIC(19,4),
    settlement_authority_currency CHAR(3),
    litigation_flag BOOLEAN NOT NULL DEFAULT FALSE,
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

-- Additive columns for databases created by the first slice.
ALTER TABLE claim ADD COLUMN IF NOT EXISTS claim_type VARCHAR(32) NOT NULL DEFAULT 'BODILY_INJURY';
ALTER TABLE claim ADD COLUMN IF NOT EXISTS settlement_authority_amount NUMERIC(19,4);
ALTER TABLE claim ADD COLUMN IF NOT EXISTS settlement_authority_currency CHAR(3);
ALTER TABLE claim ADD COLUMN IF NOT EXISTS litigation_flag BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE claim DROP CONSTRAINT IF EXISTS ck_claim_claim_type;
ALTER TABLE claim ADD CONSTRAINT ck_claim_claim_type CHECK (claim_type IN (
    'BODILY_INJURY', 'PROPERTY_DAMAGE', 'PRODUCT_LIABILITY', 'MASS_TORT', 'ENVIRONMENTAL',
    'POLLUTION', 'CONSTRUCTION_DEFECT', 'AUTO_LIABILITY', 'TRUCKING', 'WORKERS_COMPENSATION',
    'EMPLOYERS_LIABILITY', 'MEDICAL_MALPRACTICE', 'PROFESSIONAL_LIABILITY',
    'DIRECTORS_AND_OFFICERS', 'EMPLOYMENT_PRACTICES', 'CLASS_ACTION',
    'MULTI_DISTRICT_LITIGATION', 'SUBROGATION', 'OTHER'));

ALTER TABLE claim DROP CONSTRAINT IF EXISTS ck_claim_authority_non_negative;
ALTER TABLE claim ADD CONSTRAINT ck_claim_authority_non_negative
    CHECK (settlement_authority_amount IS NULL OR settlement_authority_amount >= 0);

ALTER TABLE claim DROP CONSTRAINT IF EXISTS ck_claim_authority_currency;
ALTER TABLE claim ADD CONSTRAINT ck_claim_authority_currency
    CHECK (settlement_authority_currency IS NULL OR settlement_authority_currency ~ '^[A-Z]{3}$');

CREATE INDEX IF NOT EXISTS ix_claim_tenant_status ON claim (tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_claim_policy ON claim (tenant_id, policy_id);

-- ---------------------------------------------------------------------------
-- Injury and exposure (SPEC 11.4.4 claim_injury / claim_exposure, first slice)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS claim_injury (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    claimant_party_id UUID NOT NULL,
    injury_type VARCHAR(40) NOT NULL,
    body_part VARCHAR(80),
    diagnosis_codes TEXT[],
    description TEXT,
    severity VARCHAR(16) NOT NULL,
    diagnosis_date DATE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_injury_severity CHECK (severity IN ('MILD', 'MODERATE', 'SEVERE', 'CATASTROPHIC', 'FATAL'))
);

CREATE INDEX IF NOT EXISTS ix_claim_injury_claim ON claim_injury (tenant_id, claim_id);

CREATE TABLE IF NOT EXISTS claim_exposure (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    claimant_party_id UUID NOT NULL,
    exposure_type VARCHAR(24) NOT NULL,
    product_name VARCHAR(200),
    substance_name VARCHAR(160),
    site_name VARCHAR(200),
    employer_party_id UUID,
    job_title VARCHAR(120),
    jurisdiction VARCHAR(120) NOT NULL,
    exposure_from DATE NOT NULL,
    exposure_to DATE,
    intensity VARCHAR(16),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_exposure_type CHECK (exposure_type IN (
        'PRODUCT', 'SUBSTANCE', 'SITE', 'OCCUPATION', 'ENVIRONMENT', 'MEDICAL_DEVICE', 'IMPLANT', 'RADIATION')),
    CONSTRAINT ck_exposure_dates CHECK (exposure_to IS NULL OR exposure_to >= exposure_from),
    CONSTRAINT ck_exposure_intensity CHECK (
        intensity IS NULL OR intensity IN ('LOW', 'MEDIUM', 'HIGH', 'EXTREME', 'UNKNOWN'))
);

CREATE INDEX IF NOT EXISTS ix_claim_exposure_claim ON claim_exposure (tenant_id, claim_id);

-- ---------------------------------------------------------------------------
-- Reserve history (SPEC 11.4.4 claim_reserve; append-only, current row)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS claim_reserve_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    component VARCHAR(16) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    effective_date DATE NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    reason_text TEXT,
    authority VARCHAR(120),
    sequence INTEGER NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_reserve_component CHECK (component IN (
        'INDEMNITY', 'DEFENSE', 'ALAE', 'ULAE', 'EXPENSE', 'MEDICAL', 'SUBROGATION', 'RECOVERY', 'IBNR', 'IBNER')),
    CONSTRAINT ck_reserve_amount_non_negative CHECK (amount >= 0),
    CONSTRAINT ck_reserve_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_reserve_reason CHECK (reason_code IN (
        'INITIAL', 'DEVELOPMENT', 'NEW_INFORMATION', 'SETTLEMENT_NEGOTIATION', 'COVERAGE_CHANGE',
        'PAYMENT_APPLIED', 'RECOVERY_APPLIED', 'REVIEW', 'CORRECTION', 'CLOSE')),
    CONSTRAINT uq_reserve_sequence UNIQUE (claim_id, component, sequence)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_reserve_current
    ON claim_reserve_history (claim_id, component) WHERE is_current;
CREATE INDEX IF NOT EXISTS ix_reserve_claim ON claim_reserve_history (tenant_id, claim_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Coverage decision (SPEC 11.4.4 claim_coverage_assessment and 11.4.13)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS claim_coverage_decision (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    coverage_position VARCHAR(16) NOT NULL,
    basis TEXT NOT NULL,
    policy_provisions TEXT,
    allocation_method VARCHAR(24),
    allocation_pct NUMERIC(9,6),
    decision_date DATE NOT NULL,
    reviewer_party_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'FINAL',
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_coverage_position CHECK (coverage_position IN ('COVERED', 'DENIED', 'DEFENSE_ONLY')),
    CONSTRAINT ck_coverage_status CHECK (status IN ('FINAL', 'SUPERSEDED')),
    CONSTRAINT ck_coverage_allocation_pct CHECK (allocation_pct IS NULL OR (allocation_pct >= 0 AND allocation_pct <= 1))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_coverage_final
    ON claim_coverage_decision (claim_id) WHERE status = 'FINAL';
CREATE INDEX IF NOT EXISTS ix_coverage_claim ON claim_coverage_decision (tenant_id, claim_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Litigation (SPEC 11.4.4 claim_litigation)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS claim_litigation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    matter_type VARCHAR(16) NOT NULL,
    jurisdiction VARCHAR(120) NOT NULL,
    counsel_party_id UUID NOT NULL,
    filing_date DATE NOT NULL,
    trial_date DATE,
    status VARCHAR(16) NOT NULL,
    case_number VARCHAR(64),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_litigation_matter_type CHECK (matter_type IN (
        'DEFENSE', 'COVERAGE', 'APPEAL', 'BANKRUPTCY', 'REGULATORY', 'TRANSACTIONAL')),
    CONSTRAINT ck_litigation_status CHECK (status IN (
        'ACTIVE', 'STAYED', 'DISMISSED', 'SETTLED', 'JUDGMENT', 'ON_APPEAL', 'CLOSED')),
    CONSTRAINT ck_litigation_dates CHECK (trial_date IS NULL OR trial_date >= filing_date)
);

CREATE INDEX IF NOT EXISTS ix_litigation_claim ON claim_litigation (tenant_id, claim_id);

-- ---------------------------------------------------------------------------
-- Settlement (SPEC 11.4.4 claim_settlement)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS claim_settlement (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    settlement_number VARCHAR(32) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    structure VARCHAR(24) NOT NULL,
    release_scope VARCHAR(24),
    approval_authority VARCHAR(120),
    payment_instructions TEXT,
    release_document_id UUID,
    status VARCHAR(16) NOT NULL,
    authorized_by UUID,
    authorized_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_settlement_number UNIQUE (tenant_id, settlement_number),
    CONSTRAINT ck_settlement_amount CHECK (amount > 0),
    CONSTRAINT ck_settlement_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_settlement_structure CHECK (structure IN ('LUMP_SUM', 'STRUCTURED', 'ANNUITY', 'MATRIX')),
    CONSTRAINT ck_settlement_status CHECK (status IN ('PROPOSED', 'AUTHORIZED', 'EXECUTED', 'REJECTED')),
    CONSTRAINT ck_settlement_release_scope CHECK (
        release_scope IS NULL OR release_scope IN ('FULL', 'PARTIAL', 'CLAIMANT_SPECIFIC', 'POLICY_YEAR'))
);

CREATE INDEX IF NOT EXISTS ix_settlement_claim ON claim_settlement (tenant_id, claim_id);

-- ---------------------------------------------------------------------------
-- Recovery and lien (SPEC 11.4.4 claim_recovery / claim_lien)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS claim_recovery (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    recovery_type VARCHAR(24) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    source VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    recovery_date DATE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_recovery_type CHECK (recovery_type IN (
        'SUBROGATION', 'SALVAGE', 'CONTRIBUTION', 'ASSUMED', 'OTHER_INSURANCE', 'INDEMNITY', 'TRUST',
        'GOVERNMENT', 'OTHER')),
    CONSTRAINT ck_recovery_amount CHECK (amount > 0),
    CONSTRAINT ck_recovery_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_recovery_status CHECK (status IN (
        'IDENTIFIED', 'PURSUING', 'AGREED', 'RECEIVED', 'DENIED', 'ABANDONED'))
);

CREATE INDEX IF NOT EXISTS ix_recovery_claim ON claim_recovery (tenant_id, claim_id);

CREATE TABLE IF NOT EXISTS claim_lien (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    lien_type VARCHAR(24) NOT NULL,
    lienholder VARCHAR(160) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    resolution_type VARCHAR(16),
    resolved_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_lien_type CHECK (lien_type IN (
        'MEDICARE', 'MEDICAID', 'WORKERS_COMPENSATION', 'HOSPITAL', 'PHYSICIAN', 'ERISA',
        'CHILD_SUPPORT', 'TAX', 'VETERANS', 'OTHER')),
    CONSTRAINT ck_lien_amount CHECK (amount >= 0),
    CONSTRAINT ck_lien_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_lien_status CHECK (status IN (
        'IDENTIFIED', 'VERIFIED', 'NEGOTIATING', 'RESOLVED', 'WAIVED', 'DISPUTED', 'PAID')),
    CONSTRAINT ck_lien_resolution CHECK (
        resolution_type IS NULL OR resolution_type IN ('PAID', 'NEGOTIATED', 'WAIVED', 'DEFERRED'))
);

CREATE INDEX IF NOT EXISTS ix_lien_claim ON claim_lien (tenant_id, claim_id);

-- ---------------------------------------------------------------------------
-- Claim tasks and diary notes. Both are child records of the Claim aggregate,
-- tenant- and claim-scoped, and append their domain event through the outbox in
-- the same transaction. Notes are append-only: there is no update or delete.
-- ---------------------------------------------------------------------------
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
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
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
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_claim_note_number UNIQUE (tenant_id, note_number),
    CONSTRAINT ck_claim_note_type CHECK (note_type IN (
        'DIARY', 'COVERAGE', 'RESERVE', 'LITIGATION', 'SETTLEMENT', 'GENERAL'))
);

CREATE INDEX IF NOT EXISTS ix_claim_note_created
    ON claim_note (tenant_id, claim_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Transactional outbox (SPEC 7.5.1). The canonical copy lives in
-- eia-platform/libs/src/main/resources/db/outbox_event.sql; keep both in sync.
-- Claim writes append their domain event here in the same transaction.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
