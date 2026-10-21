package com.delivery.delivery_service.mapper;

import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.dto.response.DeliveryOfferResponse;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryMapperTest {

    private final DeliveryMapper mapper = new DeliveryMapper();

    @Test
    void mapsPublicDeliveryResponseWithoutGeneratedCode() {
        Delivery delivery = new Delivery();
        delivery.setOrderId(20L);
        delivery.setShipperId(10L);
        delivery.setStatus(DeliveryStatus.ASSIGNED);
        delivery.setPickupAddress("Restaurant");
        delivery.setPickupLat(10.1);
        delivery.setPickupLng(106.1);
        delivery.setDeliveryAddress("Customer");
        delivery.setDeliveryLat(10.2);
        delivery.setDeliveryLng(106.2);
        delivery.setNotes("Call first");
        delivery.setId(1L);
        delivery.setShippingFee(new BigDecimal("100000"));
        delivery.setTotalPrice(new BigDecimal("350000"));
        delivery.setPaymentMethod("COD");
        delivery.setRestaurantId(30L);
        delivery.setShipperCurrentLat(10.15);
        delivery.setShipperCurrentLng(106.15);
        delivery.setAssignedAt(LocalDateTime.of(2026, 7, 24, 2, 0));

        DeliveryResponse response = mapper.deliveryToDeliveryResponse(delivery);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.ASSIGNED);
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getOrderId()).isEqualTo(20L);
        assertThat(response.getStatus()).isEqualTo("ASSIGNED");
        assertThat(response.getEstimatedEarnings()).isEqualByComparingTo("85000");
        assertThat(response.getPlatformCommission()).isEqualByComparingTo("15000");
        assertThat(response.getPaymentMethod()).isEqualTo("COD");
    }

    @Test
    void preservesMapStructNullAndCollectionSemantics() {
        assertThat(mapper.deliveryToDeliveryResponse(null)).isNull();
        assertThat(mapper.deliveriesToDeliveryResponses(null)).isNull();
        assertThat(mapper.deliveriesToDeliveryResponses(List.of())).isEmpty();
        assertThat(mapper.calculateShipperEarnings(null)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(mapper.calculatePlatformCommission(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void mapsOnlyTheSelfRecoveryOfferContract() {
        Delivery delivery = new Delivery();
        delivery.setId(1L);
        delivery.setOrderId(20L);
        delivery.setStatus(DeliveryStatus.WAIT_SHIPPER_CONFIRM);
        delivery.setOfferedShipperId(10L);
        delivery.setOfferExpiresAt(LocalDateTime.of(2026, 7, 25, 15, 0));
        delivery.setPickupAddress("Restaurant");
        delivery.setDeliveryAddress("Customer address");
        delivery.setShippingFee(new BigDecimal("20000"));
        delivery.setGrossShippingFee(new BigDecimal("25000"));
        delivery.setCustomerShippingFee(new BigDecimal("15000"));
        delivery.setPlatformSubsidy(new BigDecimal("5000"));
        delivery.setShopDiscount(new BigDecimal("5000"));
        UUID promotionReservationId = UUID.randomUUID();
        delivery.setPromotionReservationId(promotionReservationId);
        delivery.setTotalPrice(new BigDecimal("120000"));
        delivery.setPaymentMethod("COD");

        DeliveryOfferResponse response = mapper.deliveryToOfferResponse(delivery);

        assertThat(response.getDeliveryId()).isEqualTo(1L);
        assertThat(response.getOrderId()).isEqualTo(20L);
        assertThat(response.getStatus()).isEqualTo("WAIT_SHIPPER_CONFIRM");
        assertThat(response.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 7, 25, 15, 0));
        assertThat(response.getEstimatedEarnings()).isEqualByComparingTo("17000");
        assertThat(response.getGrossShippingFee()).isEqualByComparingTo("25000");
        assertThat(response.getCustomerShippingFee()).isEqualByComparingTo("15000");
        assertThat(response.getPlatformSubsidy()).isEqualByComparingTo("5000");
        assertThat(response.getShopDiscount()).isEqualByComparingTo("5000");
        assertThat(response.getPromotionReservationId()).isEqualTo(promotionReservationId);
        assertThat(response).hasNoNullFieldsOrPropertiesExcept(
                "pickupLat", "pickupLng", "deliveryLat", "deliveryLng", "restaurantId",
                "batchId", "pickupSequence", "dropoffSequence");
    }
}
