CREATE TABLE mint_outbox
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    deposit_id       VARCHAR(128) NOT NULL,
    event_type       VARCHAR(40)  NOT NULL DEFAULT 'MINT_REQUESTED',

    status           VARCHAR(20)  NOT NULL DEFAULT 'READY',

    claimed_by       VARCHAR(128),
    lease_token      BIGINT       NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ,

    attempt_count    INTEGER      NOT NULL DEFAULT 0,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,

    CONSTRAINT uq_mint_outbox_business
        UNIQUE (deposit_id, event_type),

    CONSTRAINT chk_mint_outbox_event
        CHECK (event_type = 'MINT_REQUESTED'),

    CONSTRAINT chk_mint_outbox_status
        CHECK (status IN (
                          'READY',
                          'CLAIMED',
                          'DEMO_DONE'
            )),

    CONSTRAINT chk_mint_outbox_token
        CHECK (lease_token >= 0),

    CONSTRAINT chk_mint_outbox_attempts
        CHECK (attempt_count >= 0),

    CONSTRAINT chk_mint_outbox_claim
        CHECK (
            (
                status = 'CLAIMED'
                    AND claimed_by IS NOT NULL
                    AND lease_expires_at IS NOT NULL
                )
                OR
            (
                status <> 'CLAIMED'
                    AND claimed_by IS NULL
                    AND lease_expires_at IS NULL
                )
            )
);

CREATE INDEX idx_mint_outbox_ready
    ON mint_outbox (created_at, id)
    WHERE status = 'READY';

CREATE INDEX idx_mint_outbox_expired
    ON mint_outbox (lease_expires_at, id)
    WHERE status = 'CLAIMED';

CREATE INDEX idx_mint_outbox_deposit
    ON mint_outbox (deposit_id);
