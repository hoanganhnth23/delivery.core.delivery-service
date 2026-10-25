-- Order-side cancellation asks Delivery first; while the hold is live the
-- shipper cannot mark the delivery PICKED_UP.
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS cancel_hold_until TIMESTAMP;
