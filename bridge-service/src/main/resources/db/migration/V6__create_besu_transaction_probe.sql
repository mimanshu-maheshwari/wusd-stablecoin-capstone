CREATE TABLE besu_transaction_probe
(
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    chain_id             BIGINT         NOT NULL,
    sender_address       VARCHAR(42)    NOT NULL,
    account_nonce        NUMERIC(78, 0) NOT NULL,

    recipient_address    VARCHAR(42)    NOT NULL,

    transaction_hash     VARCHAR(66)    NOT NULL,
    signed_raw_tx        TEXT           NOT NULL,

    status               VARCHAR(30)    NOT NULL DEFAULT 'SIGNED',

    receipt_block_hash   VARCHAR(66),
    receipt_block_number BIGINT,

    last_error           TEXT,

    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uq_besu_probe_hash
        UNIQUE (transaction_hash),

    CONSTRAINT uq_besu_probe_nonce
        UNIQUE (
                chain_id,
                sender_address,
                account_nonce
            ),

    CONSTRAINT chk_besu_probe_status
        CHECK (
            status IN (
                       'SIGNED',
                       'SUBMISSION_ACCEPTED',
                       'UNCERTAIN',
                       'INCLUDED_SUCCESS',
                       'INCLUDED_REVERTED'
                )
            ),

    CONSTRAINT chk_besu_probe_nonce
        CHECK (account_nonce >= 0)
);

CREATE INDEX idx_besu_probe_status
    ON besu_transaction_probe (status, created_at);
