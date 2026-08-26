-- Optional Hyperledger Besu integration: async on-chain anchors (same consent lifecycle as PG hash chain).
CREATE TABLE IF NOT EXISTS blockchain_integration_state (
    singleton SMALLINT PRIMARY KEY CHECK (singleton = 1),
    contract_address VARCHAR(42)
);

INSERT INTO blockchain_integration_state (singleton, contract_address)
VALUES (1, NULL)
ON CONFLICT (singleton) DO NOTHING;

CREATE TABLE IF NOT EXISTS onchain_consent_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consent_id UUID NOT NULL,
    person_id UUID NOT NULL,
    anchor_type VARCHAR(40) NOT NULL,
    consent_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    tx_hash VARCHAR(66),
    block_number BIGINT,
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_onchain_consent_outbox_pending
    ON onchain_consent_outbox (status, created_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_onchain_consent_outbox_consent
    ON onchain_consent_outbox (consent_id);
