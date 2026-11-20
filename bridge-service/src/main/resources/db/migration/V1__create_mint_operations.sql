CREATE TABLE mint_operations
(
    deposit_id     VARCHAR(128) PRIMARY KEY,
    user_id        VARCHAR(128)   NOT NULL,
    wallet_address VARCHAR(42)    NOT NULL,
    amount_units   NUMERIC(78, 0) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    status         VARCHAR(32)    NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_amount_positive CHECK (amount_units > 0),
    CONSTRAINT chk_currency_usd CHECK (currency = 'USD'),
    CONSTRAINT chk_mint_status CHECK ( status IN
                                       ('RECEIVED', 'VALIDATED', 'MINTING', 'MINTED', 'FAILED') )
);