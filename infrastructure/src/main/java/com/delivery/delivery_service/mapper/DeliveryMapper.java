package com.delivery.delivery_service.mapper;

import com.delivery.delivery_service.common.constants.PricingConstants;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.dto.response.DeliveryOfferResponse;
import com.delivery.delivery_service.entity.Delivery;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class DeliveryMapper {

    public DeliveryResponse deliveryToDeliveryResponse(Delivery delivery) {
        if (delivery == null) {
            return null;
        }

        DeliveryResponse response = new DeliveryResponse();
        response.setEstimatedEarnings(calculateShipperEarnings(delivery.getShippingFee()));
        response.setPlatformCommission(calculatePlatformCommission(delivery.getShippingFee()));
        response.setId(delivery.getId());
        response.setOrderId(delivery.getOrderId());
        response.setShipperId(delivery.getShipperId());
        response.setBatchId(delivery.getBatchId());
        response.setBatchSequence(delivery.getBatchSequence());
        response.setStatus(delivery.getStatus() == null ? null : delivery.getStatus().name());
        response.setPickupAddress(delivery.getPickupAddress());
        response.setPickupLat(delivery.getPickupLat());
        response.setPickupLng(delivery.getPickupLng());
        response.setDeliveryAddress(delivery.getDeliveryAddress());
        response.setDeliveryLat(delivery.getDeliveryLat());
        response.setDeliveryLng(delivery.getDeliveryLng());
        response.setShipperCurrentLat(delivery.getShipperCurrentLat());
        response.setShipperCurrentLng(delivery.getShipperCurrentLng());
        response.setAssignedAt(delivery.getAssignedAt());
        response.setPickedUpAt(delivery.getPickedUpAt());
        response.setDeliveredAt(delivery.getDeliveredAt());
        response.setEstimatedDeliveryTime(delivery.getEstimatedDeliveryTime());
        response.setNotes(delivery.getNotes());
        response.setCreatedAt(delivery.getCreatedAt());
        response.setUpdatedAt(delivery.getUpdatedAt());
        response.setShippingFee(delivery.getShippingFee());
        response.setGrossShippingFee(delivery.getGrossShippingFee() == null
                ? delivery.getShippingFee() : delivery.getGrossShippingFee());
        response.setCustomerShippingFee(delivery.getCustomerShippingFee() == null
                ? delivery.getShippingFee() : delivery.getCustomerShippingFee());
        response.setPlatformSubsidy(delivery.getPlatformSubsidy());
        response.setShopDiscount(delivery.getShopDiscount());
        response.setPromotionReservationId(delivery.getPromotionReservationId());
        response.setTotalPrice(delivery.getTotalPrice());
        response.setPaymentMethod(delivery.getPaymentMethod());
        response.setRestaurantId(delivery.getRestaurantId());
        return response;
    }

    public List<DeliveryResponse> deliveriesToDeliveryResponses(List<Delivery> deliveries) {
        if (deliveries == null) {
            return null;
        }

        List<DeliveryResponse> responses = new ArrayList<>(deliveries.size());
        for (Delivery delivery : deliveries) {
            responses.add(deliveryToDeliveryResponse(delivery));
        }
        return responses;
    }

    public DeliveryOfferResponse deliveryToOfferResponse(Delivery delivery) {
        if (delivery == null) {
            return null;
        }

        DeliveryOfferResponse response = new DeliveryOfferResponse();
        response.setDeliveryId(delivery.getId());
        response.setOrderId(delivery.getOrderId());
        response.setStatus(delivery.getStatus() == null ? null : delivery.getStatus().name());
        response.setExpiresAt(delivery.getOfferExpiresAt());
        response.setPickupAddress(delivery.getPickupAddress());
        response.setPickupLat(delivery.getPickupLat());
        response.setPickupLng(delivery.getPickupLng());
        response.setDeliveryAddress(delivery.getDeliveryAddress());
        response.setDeliveryLat(delivery.getDeliveryLat());
        response.setDeliveryLng(delivery.getDeliveryLng());
        response.setShippingFee(delivery.getShippingFee());
        response.setGrossShippingFee(delivery.getGrossShippingFee() == null
                ? delivery.getShippingFee() : delivery.getGrossShippingFee());
        response.setCustomerShippingFee(delivery.getCustomerShippingFee() == null
                ? delivery.getShippingFee() : delivery.getCustomerShippingFee());
        response.setPlatformSubsidy(delivery.getPlatformSubsidy());
        response.setShopDiscount(delivery.getShopDiscount());
        response.setPromotionReservationId(delivery.getPromotionReservationId());
        response.setEstimatedEarnings(calculateShipperEarnings(delivery.getShippingFee()));
        response.setTotalPrice(delivery.getTotalPrice());
        response.setPaymentMethod(delivery.getPaymentMethod());
        response.setRestaurantId(delivery.getRestaurantId());
        response.setBatchId(delivery.getBatchId());
        response.setPickupSequence(delivery.getBatchSequence());
        return response;
    }

    /**
     * ✅ Tính thu nhập shipper (85% của shipping fee)
     */
    public BigDecimal calculateShipperEarnings(BigDecimal shippingFee) {
        if (shippingFee == null) {
            return BigDecimal.ZERO;
        }
        return PricingConstants.calculateShipperEarnings(shippingFee);
    }

    /**
     * ✅ Tính hoa hồng platform (15% của shipping fee)
     */
    public BigDecimal calculatePlatformCommission(BigDecimal shippingFee) {
        if (shippingFee == null) {
            return BigDecimal.ZERO;
        }
        return PricingConstants.calculatePlatformCommission(shippingFee);
    }
}
