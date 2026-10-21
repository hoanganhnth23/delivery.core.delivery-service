ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS subtotal_price DECIMAL(12,2);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS item_discount DECIMAL(12,2);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS shop_discount DECIMAL(12,2);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS shipping_discount DECIMAL(12,2);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS customer_shipping_fee DECIMAL(12,2);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS gross_shipping_fee DECIMAL(12,2);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS platform_subsidy DECIMAL(12,2);
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS promotion_reservation_id UUID;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS promotion_breakdown TEXT;
ALTER TABLE deliveries ADD CONSTRAINT uk_deliveries_promotion_reservation
    UNIQUE (promotion_reservation_id);

UPDATE deliveries
SET gross_shipping_fee = COALESCE(gross_shipping_fee, shipping_fee, 0),
    customer_shipping_fee = COALESCE(customer_shipping_fee, shipping_fee, 0),
    subtotal_price = COALESCE(subtotal_price, total_price - COALESCE(shipping_fee, 0)),
    item_discount = COALESCE(item_discount, 0),
    shop_discount = COALESCE(shop_discount, 0),
    shipping_discount = COALESCE(shipping_discount, 0),
    platform_subsidy = COALESCE(platform_subsidy, 0)
WHERE gross_shipping_fee IS NULL OR customer_shipping_fee IS NULL OR subtotal_price IS NULL
   OR item_discount IS NULL OR shop_discount IS NULL OR shipping_discount IS NULL
   OR platform_subsidy IS NULL;
