CREATE OR REPLACE FUNCTION
    prevent_deposit_identity_change()
    RETURNS trigger
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF NEW.deposit_id IS DISTINCT FROM OLD.deposit_id
        OR NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.wallet_address IS DISTINCT FROM OLD.wallet_address
        OR NEW.amount_units IS DISTINCT FROM OLD.amount_units
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    THEN
        RAISE EXCEPTION
            'Immutable deposit fields cannot be modified';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prevent_deposit_identity_change
    BEFORE UPDATE
    ON mint_operations
    FOR EACH ROW
EXECUTE FUNCTION prevent_deposit_identity_change();
