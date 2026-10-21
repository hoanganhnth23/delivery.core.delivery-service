package com.delivery.delivery_service.entity;

public enum DeliveryStatus {
    PENDING("Chờ phân công shipper"),
    FINDING_SHIPPER("Đang tìm shipper"),
    WAIT_SHIPPER_CONFIRM("Chờ shipper nhận đơn"),
    SHIPPER_NOT_FOUND("Không tìm được shipper"),
    ASSIGNED("Đã phân công"),
    PICKED_UP("Đã lấy hàng"),
    DELIVERING("Đang giao hàng"),
    DELIVERED("Đã giao thành công"),
    /**
     * Post-pickup exception states are mutated only by DeliveryExceptionService
     * and intentionally never published on the legacy status-updated topic.
     */
    RETURNING("Đang hoàn hàng về nhà hàng"),
    RETURNED("Nhà hàng đã xác nhận nhận lại hàng"),
    CANCELLED("Đã hủy");

    private final String description;

    DeliveryStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
