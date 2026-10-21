package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.OrderCreatedEvent;
import com.delivery.delivery_service.dto.event.OrderCancelledEvent;
import com.delivery.delivery_service.dto.event.ShipperNotFoundEvent;
import com.delivery.delivery_service.dto.event.ShipperFoundEvent;
import com.delivery.delivery_service.dto.event.ExpireShipperOfferCommand;
import com.delivery.delivery_service.dto.request.AcceptDeliveryRequest;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.dto.response.DeliveryOfferResponse;
import com.delivery.delivery_service.entity.DeliveryStatus;

import java.util.List;
import com.delivery.identity.contracts.SimulationContext;

public interface DeliveryService {

    /**
     * ✅ Tạo delivery từ OrderCreatedEvent (Kafka event processing)
     */
    DeliveryResponse createDeliveryFromOrderEvent(OrderCreatedEvent event);

    /**
     * ✅ Hủy delivery và ngừng tìm shipper từ OrderCancelledEvent
     */
    void cancelDeliveryFromOrderCancelledEvent(OrderCancelledEvent event);
    
    /**
     * ✅ Cập nhật delivery status khi không tìm được shipper
     */
    void updateDeliveryStatusFromShipperNotFoundEvent(ShipperNotFoundEvent event);

    /** Persist the single active shipper offer before notifying the shipper. */
    void cacheShipperOffer(ShipperFoundEvent event);

    /** Clear only the exact expired offer generation before Saga rematches. */
    void expireShipperOffer(ExpireShipperOfferCommand command);

    /**
     * ✅ Shipper accept delivery assignment
     */
    DeliveryResponse acceptDelivery(AcceptDeliveryRequest request, Long shipperId, String role);

    DeliveryResponse acceptDelivery(AcceptDeliveryRequest request, Long principalId, Long legacyUserId, String role);

    DeliveryResponse acceptDelivery(AcceptDeliveryRequest request, Long principalId, Long legacyUserId,
                                    String role, SimulationContext simulationContext);

    /**
     * ✅ Shipper huỷ đơn SAU khi đã accept (trước khi lấy hàng).
     * Reset đơn về FINDING_SHIPPER, giải phóng shipper, và re-trigger tìm shipper
     * mới (loại trừ shipper vừa huỷ) qua cùng cơ chế rematch của Saga.
     */
    DeliveryResponse cancelAssignedDelivery(Long orderId, Long shipperId, String role, String reason);

    DeliveryResponse cancelAssignedDelivery(Long orderId, Long principalId, Long legacyUserId, String role, String reason);

    DeliveryResponse cancelAssignedDelivery(Long orderId, Long principalId, Long legacyUserId, String role,
                                            String reason, SimulationContext simulationContext);

    /**
     * Cập nhật trạng thái giao hàng
     */
    DeliveryResponse updateDeliveryStatus(Long deliveryId, DeliveryStatus status, Long userId, String role);

    DeliveryResponse updateDeliveryStatus(Long deliveryId, DeliveryStatus status, Long principalId, Long legacyUserId, String role);

    DeliveryResponse updateDeliveryStatus(Long deliveryId, DeliveryStatus status, Long principalId, Long legacyUserId,
                                          String role, SimulationContext simulationContext);

    /**
     * Lấy thông tin delivery theo ID
     */
    DeliveryResponse getDeliveryById(Long deliveryId, Long userId, String role);

    DeliveryResponse getDeliveryById(Long deliveryId, Long principalId, Long legacyUserId, String role);

    /**
     * Lấy danh sách delivery của shipper
     */
    List<DeliveryResponse> getDeliveriesByShipper(Long shipperId, Long userId, String role);

    List<DeliveryResponse> getDeliveriesByShipper(Long shipperId, Long principalId, Long legacyUserId, String role);

    /**
     * Lấy delivery theo order ID
     */
    DeliveryResponse getDeliveryByOrderId(Long orderId, Long userId, String role);

    DeliveryResponse getDeliveryByOrderId(Long orderId, Long principalId, Long legacyUserId, String role);

    /**
     * Lấy các delivery đang active của shipper
     */
    List<DeliveryResponse> getActiveDeliveriesByShipper(Long shipperId, Long userId, String role);

    List<DeliveryResponse> getActiveDeliveriesByShipper(Long shipperId, Long principalId, Long legacyUserId, String role);

    /** Return the selected shipper's single unexpired offer, or null when none exists. */
    DeliveryOfferResponse getCurrentOffer(Long shipperId, String role);

    DeliveryOfferResponse getCurrentOffer(Long principalId, Long legacyUserId, String role);
    
}
