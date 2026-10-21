package com.delivery.delivery_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;
import com.delivery.identity.contracts.SimulationContext;

@Getter
@Setter
@Entity
@Table(name = "deliveries")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "create_event_id", nullable = false, unique = true, updatable = false)
    private UUID createEventId;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "shipper_id")
    private Long shipperId; // nullable - sẽ được set khi assign shipper

    /**
     * Nullable compatibility projection. A non-null value means this delivery
     * belongs to a multi-order batch; the DeliveryBatch aggregate remains the
     * authority for batch assignment and route state.
     */
    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "batch_sequence")
    private Integer batchSequence;

    @Column(name = "offered_shipper_id")
    private Long offeredShipperId;

    @Column(name = "offer_expires_at")
    private LocalDateTime offerExpiresAt;

    @Column(name = "offered_matching_session_id", length = 36)
    private String offeredMatchingSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "pickup_address", columnDefinition = "TEXT")
    private String pickupAddress; // Địa chỉ nhà hàng

    @Column(name = "pickup_lat")
    private Double pickupLat;

    @Column(name = "pickup_lng")
    private Double pickupLng;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress; // Địa chỉ giao hàng

    @Column(name = "delivery_lat")
    private Double deliveryLat;

    @Column(name = "delivery_lng")
    private Double deliveryLng;

    @Column(name = "shipper_current_lat")
    private Double shipperCurrentLat;

    @Column(name = "shipper_current_lng")
    private Double shipperCurrentLng;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "estimated_delivery_time")
    private LocalDateTime estimatedDeliveryTime;

    @Column(name = "shipping_fee", precision = 12, scale = 2)
    private java.math.BigDecimal shippingFee; // Phí giao hàng mà shipper sẽ nhận

    @Column(name = "total_price", precision = 12, scale = 2)
    private java.math.BigDecimal totalPrice; // Tổng tiền khách phải trả (COD thu hộ)

    @Column(name = "subtotal_price", precision = 12, scale = 2)
    private java.math.BigDecimal subtotalPrice;

    @Column(name = "item_discount", precision = 12, scale = 2)
    private java.math.BigDecimal itemDiscount;

    @Column(name = "shop_discount", precision = 12, scale = 2)
    private java.math.BigDecimal shopDiscount;

    @Column(name = "shipping_discount", precision = 12, scale = 2)
    private java.math.BigDecimal shippingDiscount;

    @Column(name = "customer_shipping_fee", precision = 12, scale = 2)
    private java.math.BigDecimal customerShippingFee;

    @Column(name = "gross_shipping_fee", precision = 12, scale = 2)
    private java.math.BigDecimal grossShippingFee;

    @Column(name = "platform_subsidy", precision = 12, scale = 2)
    private java.math.BigDecimal platformSubsidy;

    @Column(name = "promotion_reservation_id", unique = true)
    private UUID promotionReservationId;

    @Column(name = "promotion_breakdown", columnDefinition = "TEXT")
    private String promotionBreakdown;

    @Column(name = "payment_method")
    private String paymentMethod; // Phương thức thanh toán (COD, MOMO, etc.)

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason; // Lý do từ chối (nếu có)

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "customer_principal_id")
    private Long customerPrincipalId;

    @Column(name = "restaurant_id")
    private Long restaurantId;

    @Column(name = "restaurant_owner_id")
    private Long restaurantOwnerId;

    @Column(name = "restaurant_owner_principal_id")
    private Long restaurantOwnerPrincipalId;

    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode = SimulationContext.ExecutionMode.REAL.name();
    @Column(name = "simulation_run_id")
    private UUID simulationRunId;
    @Column(name = "simulation_cohort_id")
    private UUID simulationCohortId;
    @Column(name = "simulation_binding_version")
    private Long simulationBindingVersion;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public SimulationContext getSimulationContext() {
        if (!SimulationContext.ExecutionMode.SIMULATION.name().equals(executionMode)) return SimulationContext.real();
        return new SimulationContext(SimulationContext.ExecutionMode.SIMULATION, simulationRunId,
                simulationCohortId, simulationBindingVersion);
    }

    public void setSimulationContext(SimulationContext context) {
        SimulationContext normalized = SimulationContext.orReal(context);
        normalized.requireValid();
        executionMode = normalized.mode().name();
        simulationRunId = normalized.runId();
        simulationCohortId = normalized.cohortId();
        simulationBindingVersion = normalized.bindingVersion();
    }
}
