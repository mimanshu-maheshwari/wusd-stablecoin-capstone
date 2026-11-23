CREATE TABLE kafka_inbox
(
    topic_name       VARCHAR(249) NOT NULL,
    partition_number INTEGER      NOT NULL,
    record_offset    BIGINT       NOT NULL,

    record_key       VARCHAR(128) NOT NULL,
    deposit_id       VARCHAR(128) NOT NULL,
    payload_sha256   VARCHAR(64)  NOT NULL,

    processed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_kafka_inbox
        PRIMARY KEY (
                     topic_name,
                     partition_number,
                     record_offset
            ),

    CONSTRAINT chk_inbox_partition
        CHECK (partition_number >= 0),

    CONSTRAINT chk_inbox_offset
        CHECK (record_offset >= 0),

    CONSTRAINT chk_inbox_payload_sha256
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_kafka_inbox_deposit
    ON kafka_inbox (deposit_id);

CREATE INDEX idx_kafka_inbox_processed_at
    ON kafka_inbox (processed_at);
