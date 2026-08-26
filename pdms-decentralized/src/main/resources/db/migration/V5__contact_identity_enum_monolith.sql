-- Upgrade path only: older decentralized installs used TEXT + CHECK for contact_identity.type.
-- Current V1 matches pdms-monolith (ENUM from install). This script is a no-op when already ENUM.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'contact_identity'
          AND column_name = 'type'
          AND udt_name = 'contact_identity_type'
    ) THEN
        NULL;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'contact_identity_type') THEN
            CREATE TYPE contact_identity_type AS ENUM ('PHONE', 'EMAIL', 'OIDC');
        END IF;

        ALTER TABLE contact_identity DROP CONSTRAINT IF EXISTS chk_contact_identity_type;
        ALTER TABLE contact_identity
            ALTER COLUMN type TYPE contact_identity_type USING (type::contact_identity_type);

        ALTER TABLE person_snapshot_contact_identity DROP CONSTRAINT IF EXISTS chk_snapshot_contact_type;
        ALTER TABLE person_snapshot_contact_identity
            ALTER COLUMN type TYPE contact_identity_type USING (type::contact_identity_type);
    END IF;
END $$;
