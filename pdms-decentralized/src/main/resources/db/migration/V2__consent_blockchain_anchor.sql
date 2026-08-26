-- ===============================================================
-- Consent blockchain-like anchor ledger
-- ===============================================================
-- Keeps PostgreSQL/CockroachDB as source of truth and records
-- tamper-evident consent lifecycle anchors as an append-only hash chain.

CREATE TABLE IF NOT EXISTS consent_ledger_anchor (
    id UUID PRIMARY KEY,
    consent_id UUID NOT NULL,
    person_id UUID NOT NULL,
    anchor_type VARCHAR(40) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    previous_hash VARCHAR(64),
    chain_hash VARCHAR(64) NOT NULL,
    anchored_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_consent_ledger_anchor_consent_time
    ON consent_ledger_anchor(consent_id, anchored_at DESC);

CREATE INDEX IF NOT EXISTS idx_consent_ledger_anchor_person_time
    ON consent_ledger_anchor(person_id, anchored_at DESC);
