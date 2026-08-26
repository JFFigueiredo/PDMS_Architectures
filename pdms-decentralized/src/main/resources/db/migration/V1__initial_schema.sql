-- ===============================================================
-- PDMS INITIAL SCHEMA - Complete Database Schema (decentralized module)
-- ===============================================================
-- Same normalized + snapshot + audit + outbox DDL as pdms-monolith V1.
-- Besu / on-chain consent tables are added in V2+ (decentralized-only).
-- ===============================================================

-- ===============================================================
-- PART 1: NORMALIZED SCHEMA (3NF) - Relational Design
-- ===============================================================

DROP TABLE IF EXISTS consent CASCADE;
DROP TABLE IF EXISTS preferences CASCADE;
DROP TABLE IF EXISTS address CASCADE;
DROP TABLE IF EXISTS contact_identity CASCADE;
DROP TABLE IF EXISTS user_account CASCADE;
DROP TABLE IF EXISTS person CASCADE;

-- 1. PERSON
CREATE TABLE person (
    id UUID PRIMARY KEY,
    status TEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. USER ACCOUNT
CREATE TABLE user_account (
    id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    status TEXT NOT NULL,
    type TEXT NOT NULL,
    password_hash TEXT,
    password_salt TEXT,
    version INT NOT NULL DEFAULT 1,
    first_name TEXT,
    last_name TEXT,
    surname TEXT,
    tax_number TEXT,
    date_of_birth TIMESTAMP,
    gender TEXT,
    nationality TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_account_person FOREIGN KEY (person_id)
        REFERENCES person(id) ON DELETE CASCADE
);

-- 3. CONTACT IDENTITY (matches pdms-monolith: ENUM + unique type,value)
DROP TYPE IF EXISTS contact_identity_type CASCADE;
CREATE TYPE contact_identity_type AS ENUM ('PHONE', 'EMAIL', 'OIDC');

CREATE TABLE contact_identity (
    id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    type contact_identity_type NOT NULL,
    value TEXT NOT NULL,
    verification_date TIMESTAMP,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_contact_identity_person FOREIGN KEY (person_id)
        REFERENCES person(id) ON DELETE CASCADE,
    CONSTRAINT uk_contact_identity_value UNIQUE (type, value)
);

-- 4. ADDRESS
CREATE TABLE address (
    id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    address_type TEXT,
    country TEXT,
    postal_code TEXT,
    city TEXT,
    street TEXT,
    street_number TEXT,
    flat TEXT,
    company_name TEXT,
    additional_information TEXT,
    verified_address BOOLEAN NOT NULL DEFAULT FALSE,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_address_person FOREIGN KEY (person_id)
        REFERENCES person(id) ON DELETE CASCADE
);

-- 5. CONSENT
CREATE TABLE consent (
    id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    consent_type TEXT NOT NULL,
    granted BOOLEAN NOT NULL,
    granted_at TIMESTAMP,
    expires_at TIMESTAMP,
    consent_method TEXT,
    consent_version TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    withdrawal_reason TEXT,
    withdrawn_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT fk_consent_person FOREIGN KEY (person_id)
        REFERENCES person(id) ON DELETE CASCADE
);

-- 6. PREFERENCES
CREATE TABLE preferences (
    id UUID PRIMARY KEY,
    person_id UUID NOT NULL UNIQUE,
    language TEXT DEFAULT 'en',
    nickname TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_preferences_person FOREIGN KEY (person_id)
        REFERENCES person(id) ON DELETE CASCADE
);

-- Indexes
CREATE INDEX idx_person_status ON person(status);
CREATE INDEX idx_person_created_at ON person(created_at);
CREATE INDEX idx_user_account_person_id ON user_account(person_id);
CREATE INDEX idx_user_account_status ON user_account(status);
CREATE INDEX idx_user_account_type ON user_account(type);
CREATE INDEX idx_user_account_tax_number ON user_account(tax_number);
CREATE INDEX idx_contact_identity_person_id ON contact_identity(person_id);
CREATE INDEX idx_contact_identity_type ON contact_identity(type);
CREATE INDEX idx_contact_identity_value ON contact_identity(value);
CREATE INDEX idx_contact_identity_is_primary ON contact_identity(is_primary) WHERE is_primary = TRUE;
CREATE INDEX idx_address_person_id ON address(person_id);
CREATE INDEX idx_address_country ON address(country);
CREATE INDEX idx_address_postal_code ON address(postal_code);
CREATE INDEX idx_address_is_primary ON address(is_primary) WHERE is_primary = TRUE;
CREATE INDEX idx_consent_person_id ON consent(person_id);
CREATE INDEX idx_consent_consent_type ON consent(consent_type);
CREATE INDEX idx_consent_granted ON consent(granted);
CREATE INDEX idx_consent_is_active ON consent(is_active) WHERE is_active = TRUE;
CREATE INDEX idx_preferences_person_id ON preferences(person_id);
CREATE INDEX idx_preferences_language ON preferences(language);

-- ===============================================================
-- PART 2: SNAPSHOT SCHEMA
-- ===============================================================

DROP TABLE IF EXISTS person_snapshot_consent CASCADE;
DROP TABLE IF EXISTS person_snapshot_preferences CASCADE;
DROP TABLE IF EXISTS person_snapshot_address CASCADE;
DROP TABLE IF EXISTS person_snapshot_contact_identity CASCADE;
DROP TABLE IF EXISTS person_snapshot_user_account CASCADE;
DROP TABLE IF EXISTS person_snapshot CASCADE;

CREATE TABLE person_snapshot (
    snapshot_id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    snapshot_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    person_status TEXT NOT NULL,
    person_version INT NOT NULL,
    person_created_at TIMESTAMP NOT NULL,
    person_updated_at TIMESTAMP NOT NULL
);

CREATE TABLE person_snapshot_user_account (
    snapshot_id UUID NOT NULL,
    person_id UUID NOT NULL,
    id UUID NOT NULL,
    status TEXT NOT NULL,
    type TEXT NOT NULL,
    version INT NOT NULL,
    first_name TEXT,
    last_name TEXT,
    surname TEXT,
    tax_number TEXT,
    date_of_birth TIMESTAMP,
    gender TEXT,
    nationality TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (snapshot_id, id),
    CONSTRAINT fk_snapshot_user_account_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES person_snapshot(snapshot_id) ON DELETE CASCADE
);

CREATE TABLE person_snapshot_contact_identity (
    snapshot_id UUID NOT NULL,
    person_id UUID NOT NULL,
    id UUID NOT NULL,
    type contact_identity_type NOT NULL,
    value TEXT NOT NULL,
    verification_date TIMESTAMP,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (snapshot_id, id),
    CONSTRAINT fk_snapshot_contact_identity_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES person_snapshot(snapshot_id) ON DELETE CASCADE
);

CREATE TABLE person_snapshot_address (
    snapshot_id UUID NOT NULL,
    person_id UUID NOT NULL,
    id UUID NOT NULL,
    address_type TEXT,
    country TEXT,
    postal_code TEXT,
    city TEXT,
    street TEXT,
    street_number TEXT,
    flat TEXT,
    company_name TEXT,
    additional_information TEXT,
    verified_address BOOLEAN NOT NULL DEFAULT FALSE,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (snapshot_id, id),
    CONSTRAINT fk_snapshot_address_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES person_snapshot(snapshot_id) ON DELETE CASCADE
);

CREATE TABLE person_snapshot_consent (
    snapshot_id UUID NOT NULL,
    person_id UUID NOT NULL,
    id UUID NOT NULL,
    consent_type TEXT NOT NULL,
    granted BOOLEAN NOT NULL,
    granted_at TIMESTAMP,
    expires_at TIMESTAMP,
    consent_method TEXT,
    consent_version TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    withdrawal_reason TEXT,
    withdrawn_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version INT NOT NULL,
    PRIMARY KEY (snapshot_id, id),
    CONSTRAINT fk_snapshot_consent_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES person_snapshot(snapshot_id) ON DELETE CASCADE
);

CREATE TABLE person_snapshot_preferences (
    snapshot_id UUID NOT NULL,
    person_id UUID NOT NULL,
    id UUID NOT NULL,
    language TEXT,
    nickname TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (snapshot_id, id),
    CONSTRAINT fk_snapshot_preferences_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES person_snapshot(snapshot_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_person_snapshot_person_id ON person_snapshot(person_id);
CREATE INDEX IF NOT EXISTS idx_person_snapshot_timestamp ON person_snapshot(snapshot_timestamp);
CREATE INDEX IF NOT EXISTS idx_person_snapshot_person_timestamp ON person_snapshot(person_id, snapshot_timestamp DESC);

-- ===============================================================
-- PART 3: AUDIT SCHEMA
-- ===============================================================

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    event_timestamp TIMESTAMP NOT NULL,
    user_id VARCHAR(255),
    entity_type VARCHAR(100),
    entity_id UUID,
    http_method VARCHAR(10),
    request_path VARCHAR(500),
    ip_address VARCHAR(50),
    event_data JSONB,
    status VARCHAR(20),
    error_message VARCHAR(1000),
    CONSTRAINT chk_status CHECK (status IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_audit_event_timestamp ON audit_event(event_timestamp);
CREATE INDEX idx_audit_event_type ON audit_event(event_type);
CREATE INDEX idx_audit_event_user ON audit_event(user_id);
CREATE INDEX idx_audit_event_entity ON audit_event(entity_type, entity_id);

-- ===============================================================
-- PART 4: OUTBOX TABLE
-- ===============================================================

CREATE TABLE IF NOT EXISTS outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT chk_outbox_status CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox(processing_status, created_at)
    WHERE processing_status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate ON outbox(aggregate_type, aggregate_id);
