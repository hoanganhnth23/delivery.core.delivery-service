package com.delivery.delivery_service.common.constants;

/**
 * ✅ Kafka Topic Constants theo Backend Instructions
 */
public class KafkaTopicConstants {
    
    // ✅ Outgoing topics to other services
    public static final String DELIVERY_STATUS_UPDATED_TOPIC = "delivery.status-updated";
    public static final String SHIPPER_ACCEPTED_TOPIC = "delivery.shipper-accepted";
    public static final String DELIVERY_COMPLETED_TOPIC = "delivery.completed";
    
    // ✅ NEW: Thay thế REST call đến tracking-service
    public static final String SHIPPER_STATUS_CHANGE_TOPIC = "shipper.status-change";

    // ✅ NEW: Shipper rejected event — triggers re-assignment flow
    public static final String SHIPPER_REJECTED_TOPIC = "delivery.shipper-rejected";
    public static final String SHIPPER_OFFERED_TOPIC = "delivery.shipper-offered";
    public static final String OFFER_PERSISTED_TOPIC = "delivery.offer-persisted";
    public static final String OFFER_RETIRED_TOPIC = "delivery.offer-retired";
    public static final String BATCH_ACCEPTED_TOPIC = "delivery.batch.accepted";
    public static final String BATCH_RELEASED_TOPIC = "delivery.batch.released";
    public static final String BATCH_COMPLETED_TOPIC = "delivery.batch.completed";
    /** Dedicated post-pickup exception contract; never reuse status-updated. */
    public static final String DELIVERY_EXCEPTION_REPORTED_TOPIC = "delivery.exception.reported";
    
    private KafkaTopicConstants() {
        // Utility class
    }
}
