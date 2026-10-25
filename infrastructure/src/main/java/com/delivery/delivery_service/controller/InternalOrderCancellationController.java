package com.delivery.delivery_service.controller;

import com.delivery.delivery_service.payload.BaseResponse;
import com.delivery.delivery_service.service.OrderCancellationHoldService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Order-service asks here before it cancels an order (service-to-service only). */
@RestController
@RequestMapping("/api/deliveries/internal/orders")
public class InternalOrderCancellationController {

    private final OrderCancellationHoldService holds;
    private final String internalSecret;

    public InternalOrderCancellationController(OrderCancellationHoldService holds,
                                               @Value("${app.internal.secret:}") String internalSecret) {
        this.holds = holds;
        this.internalSecret = internalSecret;
    }

    @PostMapping("/{orderId}/cancellation-hold")
    public ResponseEntity<BaseResponse<OrderCancellationHoldService.Decision>> requestCancellationHold(
            @PathVariable Long orderId,
            @RequestHeader(value = "Internal-Token", required = false) String internalToken) {
        if (internalSecret == null || internalSecret.isBlank() || !internalSecret.equals(internalToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new BaseResponse<>(0, null, "Forbidden"));
        }
        OrderCancellationHoldService.Decision decision = holds.requestHold(orderId);
        if (!decision.allowed()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new BaseResponse<>(0, decision, decision.message()));
        }
        return ResponseEntity.ok(new BaseResponse<>(1, decision, decision.message()));
    }
}
