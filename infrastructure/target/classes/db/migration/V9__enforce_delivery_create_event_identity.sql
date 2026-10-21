DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM deliveries WHERE create_event_id IS NULL) THEN
        RAISE EXCEPTION 'Existing deliveries require create-event reconciliation before V9';
    END IF;
END $$;

ALTER TABLE deliveries
    ALTER COLUMN create_event_id SET NOT NULL;

ALTER TABLE deliveries
    ADD CONSTRAINT uk_deliveries_create_event_id UNIQUE (create_event_id);
