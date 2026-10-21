package com.delivery.delivery_service.dto;

import com.delivery.delivery_service.dto.request.AcceptDeliveryRequest;
import com.delivery.delivery_service.dto.request.CancelDeliveryAssignmentRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryCommandValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptRequiresOrderAndCanonicalAction() {
        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setAction("MAYBE");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("orderId", "action");
    }

    @Test
    void acceptBoundsVietnamLocationAndPickupEstimate() {
        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setOrderId(9L);
        request.setAction("ACCEPT");
        request.setCurrentLat(40.0);
        request.setCurrentLng(80.0);
        request.setEstimatedPickupTime(300.0);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("currentLat", "currentLng", "estimatedPickupTime");
    }

    @Test
    void cancelRequiresOrderButDoesNotRequireAcceptAction() {
        CancelDeliveryAssignmentRequest request = new CancelDeliveryAssignmentRequest();

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("orderId");
    }
}
