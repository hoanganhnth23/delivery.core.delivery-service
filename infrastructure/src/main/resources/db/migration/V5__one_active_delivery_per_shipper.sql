DO $$
BEGIN
    IF EXISTS (
        SELECT shipper_id
        FROM deliveries
        WHERE shipper_id IS NOT NULL
          AND status IN ('ASSIGNED', 'PICKED_UP', 'DELIVERING')
        GROUP BY shipper_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot enforce one active delivery per shipper: duplicate active assignments exist';
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_deliveries_one_active_per_shipper
    ON deliveries (shipper_id)
    WHERE shipper_id IS NOT NULL
      AND status IN ('ASSIGNED', 'PICKED_UP', 'DELIVERING');
