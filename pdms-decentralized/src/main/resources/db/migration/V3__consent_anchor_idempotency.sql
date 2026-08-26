-- Ensure idempotency for retries when anchoring consent events.
CREATE UNIQUE INDEX IF NOT EXISTS uk_consent_ledger_anchor_event
    ON consent_ledger_anchor(consent_id, anchor_type, payload_hash);
