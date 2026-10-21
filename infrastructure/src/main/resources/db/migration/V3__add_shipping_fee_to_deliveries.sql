-- ✅ Migration Script: Add shipping_fee to deliveries table
-- Delivery Service Database

-- Add shipping_fee column
ALTER TABLE deliveries 
ADD COLUMN IF NOT EXISTS shipping_fee DECIMAL(12,2);

-- Set default value for existing records (15000 VND default)
UPDATE deliveries 
SET shipping_fee = 15000.00 
WHERE shipping_fee IS NULL;

-- Add comment for documentation
COMMENT ON COLUMN deliveries.shipping_fee IS 'Phí giao hàng mà shipper sẽ nhận được khi hoàn thành đơn';

-- Optional: Add index if querying by shipping_fee
-- CREATE INDEX IF NOT EXISTS idx_deliveries_shipping_fee ON deliveries(shipping_fee);
