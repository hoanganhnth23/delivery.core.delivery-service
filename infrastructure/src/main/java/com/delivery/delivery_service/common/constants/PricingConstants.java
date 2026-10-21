package com.delivery.delivery_service.common.constants;

import java.math.BigDecimal;

/**
 * ✅ Platform Pricing Constants
 * Commission và phí nền tảng
 */
public class PricingConstants {
    
    // ✅ Platform commission (15% - Grab thường lấy 15-20%)
    public static final BigDecimal PLATFORM_COMMISSION_RATE = new BigDecimal("0.15");
    
    // ✅ Restaurant commission (20% - Phí hoa hồng trên giá món ăn)
    public static final BigDecimal RESTAURANT_COMMISSION_RATE = new BigDecimal("0.20");
    
    // ✅ Shipper earnings rate (85% còn lại)
    public static final BigDecimal SHIPPER_EARNINGS_RATE = new BigDecimal("0.85");
    
    private PricingConstants() {
        // Utility class
    }
    
    /**
     * Calculate shipper earnings từ shipping fee
     * Shipper nhận 85% của shipping fee
     */
    public static BigDecimal calculateShipperEarnings(BigDecimal shippingFee) {
        if (shippingFee == null || shippingFee.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("shippingFee must be positive");
        }
        return shippingFee.multiply(SHIPPER_EARNINGS_RATE);
    }
    
    /**
     * Calculate platform commission từ shipping fee
     * Platform lấy 15% của shipping fee
     */
    public static BigDecimal calculatePlatformCommission(BigDecimal shippingFee) {
        if (shippingFee == null || shippingFee.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("shippingFee must be positive");
        }
        return shippingFee.multiply(PLATFORM_COMMISSION_RATE);
    }
}
