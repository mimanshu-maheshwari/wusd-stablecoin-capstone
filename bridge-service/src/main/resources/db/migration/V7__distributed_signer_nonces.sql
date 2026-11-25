CREATE TABLE signer_nonce_cursor
(
    chain_id       BIGINT         NOT NULL,
    signer_address VARCHAR(42)    NOT NULL,
    next_nonce     NUMERIC(78, 0) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),

    PRIMARY KEY (chain_id, signer_address),
    CHECK (next_nonce >= 0)
);

CREATE TABLE signer_nonce_reservation
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    chain_id       BIGINT         NOT NULL,
    signer_address VARCHAR(42)    NOT NULL,
    account_nonce  NUMERIC(78, 0) NOT NULL,

    outbox_id      BIGINT         NOT NULL UNIQUE,

    status         VARCHAR(32)    NOT NULL DEFAULT 'RESERVED',

    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uq_signer_nonce
        UNIQUE (chain_id, signer_address, account_nonce),

    CONSTRAINT fk_signer_nonce_cursor
        FOREIGN KEY (chain_id, signer_address)
            REFERENCES signer_nonce_cursor (
                                            chain_id, signer_address
                ),

    CONSTRAINT chk_signer_reserved_nonce
        CHECK (account_nonce >= 0),

    CONSTRAINT chk_signer_reservation_status
        CHECK (status IN (
                          'RESERVED',
                          'SIGNED',
                          'BROADCAST_UNKNOWN',
                          'SUBMITTED',
                          'INCLUDED_SUCCESS',
                          'INCLUDED_REVERTED',
                          'RECONCILIATION_REQUIRED'
            ))
);

CREATE TABLE signed_transaction_attempt
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    reservation_id   BIGINT         NOT NULL REFERENCES
        signer_nonce_reservation (id),

    attempt_number   INTEGER        NOT NULL,

    transaction_hash VARCHAR(66)    NOT NULL UNIQUE,
    signed_raw_tx    TEXT           NOT NULL,

    recipient        VARCHAR(42)    NOT NULL,
    value_wei        NUMERIC(78, 0) NOT NULL,
    gas_limit        NUMERIC(78, 0) NOT NULL,
    gas_price_wei    NUMERIC(78, 0) NOT NULL,
    calldata         TEXT           NOT NULL,

    status           VARCHAR(32)    NOT NULL DEFAULT 'SIGNED',

    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uq_signed_attempt_number
        UNIQUE (reservation_id, attempt_number),

    CONSTRAINT chk_signed_attempt_number
        CHECK (attempt_number >= 1),

    CONSTRAINT chk_signed_attempt_status
        CHECK (status IN (
                          'SIGNED',
                          'BROADCAST_UNKNOWN',
                          'SUBMITTED',
                          'INCLUDED_SUCCESS',
                          'INCLUDED_REVERTED'
            )),

    CONSTRAINT chk_signed_attempt_amounts
        CHECK (
            value_wei >= 0
                AND gas_limit > 0
                AND gas_price_wei >= 0
            )
);

CREATE INDEX idx_nonce_reservation_status
    ON signer_nonce_reservation (status, updated_at);

CREATE INDEX idx_signed_attempt_reservation
    ON signed_transaction_attempt (reservation_id, attempt_number DESC);
