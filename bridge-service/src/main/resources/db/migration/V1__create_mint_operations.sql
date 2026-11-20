CREATE TABLE mint_operations (

  -- Immutable business identifier; primary key
  deposit_id VARCHAR(128) PRIMARY KEY,

  -- Customer reference
  user_id VARCHAR(128) NOT NULL,

  -- Destination Ethereum wallet
  wallet_address VARCHAR(42) NOT NULL,

  -- Exact integer base-unit amount
  amount_units NUMERIC(78, 0) NOT NULL,

  -- Fiat currency, currently USD only
  currency VARCHAR(3) NOT NULL,

  -- Current mint workflow state
  status VARCHAR(32) NOT NULL,

  -- Record creation timestamp
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- Record modification timestamp
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT chk_amount_positive
  CHECK (amount_units > 0),

  CONSTRAINT chk_currency_usd
  CHECK (currency = 'USD'),

  CONSTRAINT chk_mint_status
  CHECK (
    status IN (
      'RECEIVED',
      'VALIDATED',
      'MINTING',
      'MINTED',
      'FAILED'
    )
  )
);
