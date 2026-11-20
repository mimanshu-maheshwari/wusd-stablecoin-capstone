-- Needed for SHA-256 digest() during data backfill.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Add new columns as nullable initially.
ALTER TABLE mint_operations
    ADD COLUMN payload_hash   VARCHAR(64),
    ADD COLUMN status_version BIGINT NOT NULL DEFAULT 0;

-- Backfill fingerprints for deposits created in Lesson 3.
-- Canonical representation:
-- domain, depositId, userId, lowercase wallet,
-- integer amount, currency
-- separated by newline characters.

UPDATE mint_operations
SET payload_hash = encode(
        digest(
                concat_ws(
                        E'\n',
                        'wusd-deposit-v1',
                        deposit_id,
                        user_id,
                        lower(wallet_address),
                        amount_units::text,
                        currency
                ),
                'sha256'
        ),
        'hex'
                   )
WHERE payload_hash IS NULL;

-- Make fingerprint mandatory for future inserts.
ALTER TABLE mint_operations
    ALTER COLUMN payload_hash SET NOT NULL;

-- Basic format and version constraints.
ALTER TABLE mint_operations
    ADD CONSTRAINT chk_payload_hash_format
        CHECK (payload_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE mint_operations
    ADD CONSTRAINT chk_status_version_nonnegative
        CHECK (status_version >= 0);
