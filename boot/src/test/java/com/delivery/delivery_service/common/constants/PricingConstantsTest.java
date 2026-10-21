package com.delivery.delivery_service.common.constants;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingConstantsTest {

    @Test
    void lowPositiveFeeStillUsesExactEightyFiveFifteenSplit() {
        BigDecimal fee = new BigDecimal("5000");
        BigDecimal shipper = PricingConstants.calculateShipperEarnings(fee);
        BigDecimal platform = PricingConstants.calculatePlatformCommission(fee);

        assertThat(shipper).isEqualByComparingTo("4250.00");
        assertThat(platform).isEqualByComparingTo("750.00");
        assertThat(shipper.add(platform)).isEqualByComparingTo(fee);
    }

    @Test
    void nonPositiveOrMissingFeeFailsClosed() {
        assertThatThrownBy(() -> PricingConstants.calculateShipperEarnings(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PricingConstants.calculateShipperEarnings(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PricingConstants.calculatePlatformCommission(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
