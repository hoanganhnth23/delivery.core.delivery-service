package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ✅ Event Validation Service theo Backend Instructions
 * Validate incoming events để đảm bảo data integrity
 */
@Slf4j
@Service
public class EventValidationService {
    
    /**
     * Validate OrderCreatedEvent và return validation results
     */
    public ValidationResult validateOrderCreatedEvent(OrderCreatedEvent event) {
        if (event == null) {
            return ValidationResult.invalid("OrderCreatedEvent không được null");
        }
        
        List<String> errors = new ArrayList<>();
        
        // Validate required fields
        if (event.getEventId() == null) {
            errors.add("Event ID không được null");
        }

        if (event.getOrderId() == null || event.getOrderId() <= 0) {
            errors.add("Order ID không được null hoặc <= 0");
        }
        
        if (event.getUserId() == null || event.getUserId() <= 0) {
            errors.add("User ID không được null hoặc <= 0");
        }
        
        if (event.getRestaurantId() == null || event.getRestaurantId() <= 0) {
            errors.add("Restaurant ID không được null hoặc <= 0");
        }

        if (event.getCreatorId() == null || event.getCreatorId() <= 0) {
            errors.add("Restaurant owner ID không được null hoặc <= 0");
        }
        
        if (event.getStatus() == null || event.getStatus().trim().isEmpty()) {
            errors.add("Status không được null hoặc rỗng");
        }
        
        // Validate financial fields
        if (event.getSubtotalPrice() == null || event.getSubtotalPrice().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("Subtotal price phải lớn hơn 0");
        }
        
        if (event.getTotalPrice() == null || event.getTotalPrice().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("Total price phải lớn hơn 0");
        }

        if (event.getShippingFee() == null || event.getShippingFee().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("Shipping fee phải lớn hơn 0");
        }

        if (event.getDiscountAmount() == null
                || event.getDiscountAmount().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("Discount amount không được null hoặc âm");
        }
        
        if (!"COD".equals(event.getPaymentMethod())) {
            errors.add("Payment method phải là COD trong MVP");
        }

        if (event.getSubtotalPrice() != null && event.getShippingFee() != null
                && event.getDiscountAmount() != null && event.getTotalPrice() != null) {
            BigDecimal itemDiscount = event.getItemDiscount() == null
                    ? event.getDiscountAmount() : event.getItemDiscount();
            BigDecimal customerShipping = event.getCustomerShippingFee() == null
                    ? event.getShippingFee() : event.getCustomerShippingFee();
            if (event.getTotalPrice().compareTo(event.getSubtotalPrice()
                    .subtract(itemDiscount).add(customerShipping)) != 0) {
                errors.add("Total price không khớp subtotal - item discount + customer shipping");
            }
        }
        
        // Validate address fields
        if (event.getDeliveryAddress() == null || event.getDeliveryAddress().trim().length() < 10) {
            errors.add("Delivery address phải có ít nhất 10 ký tự");
        }
        
        // if (event.getRestaurantName() == null || event.getRestaurantName().trim().length() < 2) {
        //     errors.add("Restaurant name phải có ít nhất 2 ký tự");
        // }
        
        if (event.getRestaurantAddress() == null || event.getRestaurantAddress().trim().length() < 10) {
            errors.add("Restaurant address phải có ít nhất 10 ký tự");
        }
        
        // Validate customer info
        // if (event.getCustomerName() == null || event.getCustomerName().trim().length() < 2) {
        //     errors.add("Customer name phải có ít nhất 2 ký tự");
        // }
        
        // if (event.getCustomerPhone() == null || !event.getCustomerPhone().matches("^[0-9]{10,11}$")) {
        //     errors.add("Customer phone phải là 10-11 chữ số");
        // }
        
        if (!isFiniteInRange(event.getDeliveryLat(), 8.0, 24.0)
                || !isFiniteInRange(event.getPickupLat(), 8.0, 24.0)
                || !isFiniteInRange(event.getDeliveryLng(), 102.0, 110.0)
                || !isFiniteInRange(event.getPickupLng(), 102.0, 110.0)) {
            errors.add("Pickup/delivery coordinates phải có đủ và nằm trong phạm vi Việt Nam");
        }
        
        // if (event.getCreatedAt() == null) {
        //     errors.add("Created at không được null");
        // }
        
        if (errors.isEmpty()) {
            log.debug("✅ OrderCreatedEvent validation passed for order: {}", event.getOrderId());
            return ValidationResult.valid();
        }
        
        String errorMessage = String.join("; ", errors);
        log.warn("⚠️ OrderCreatedEvent validation failed for order: {} - Errors: {}", 
                event.getOrderId(), errorMessage);
        
        return ValidationResult.invalid(errorMessage);
    }

    private boolean isFiniteInRange(Double value, double min, double max) {
        return value != null && Double.isFinite(value) && value >= min && value <= max;
    }
    
    /**
     * Validation result wrapper
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        
        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }
        
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }
        
        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
