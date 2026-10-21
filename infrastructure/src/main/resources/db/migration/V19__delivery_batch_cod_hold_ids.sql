ALTER TABLE delivery_batches
    ADD COLUMN IF NOT EXISTS cod_hold_ids TEXT NULL;
