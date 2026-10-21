package com.delivery.delivery_service.controller;

import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.repository.DeliveryRepository;
import com.delivery.delivery_service.payload.BaseResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/deliveries/internal")
public class InternalDeliveryController {

    private static final Set<DeliveryStatus> TRACKABLE_STATUSES = Set.of(
            DeliveryStatus.ASSIGNED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.DELIVERING,
            DeliveryStatus.RETURNING);

    private final DeliveryRepository deliveryRepository;
    private final String internalSecret;

    public InternalDeliveryController(
            DeliveryRepository deliveryRepository,
            @Value("${app.internal.secret:}") String internalSecret) {
        this.deliveryRepository = deliveryRepository;
        this.internalSecret = internalSecret;
    }

    @GetMapping("/{deliveryId}/tracking-access")
    public ResponseEntity<BaseResponse<Boolean>> canTrack(
            @PathVariable Long deliveryId,
            @RequestParam Long userId,
            @RequestParam String role,
            @RequestParam Long shipperId,
            @RequestHeader(value = "Internal-Token", required = false) String internalToken) {
        if (internalSecret == null || internalSecret.isBlank()
                || !internalSecret.equals(internalToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new BaseResponse<>(0, null, "Forbidden"));
        }

        Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null
                || delivery.getShipperId() == null
                || !delivery.getShipperId().equals(shipperId)
                || !TRACKABLE_STATUSES.contains(delivery.getStatus())) {
            return ResponseEntity.ok(new BaseResponse<>(1, false));
        }

        boolean allowed = switch (role) {
            case "ADMIN" -> true;
            case "USER" -> delivery.getCreatorId().equals(userId);
            case "SHIPPER" -> delivery.getShipperId().equals(userId);
            case "SHOP_OWNER" -> userId != null && userId.equals(delivery.getRestaurantOwnerId());
            default -> false;
        };
        return ResponseEntity.ok(new BaseResponse<>(1, allowed));
    }

    /** Private recovery lookup; exposes only identity and terminal state. */
    @GetMapping("/simulation-runs/{runId}/deliveries")
    public ResponseEntity<BaseResponse<List<SimulationDeliveryStatus>>> findSimulationRunDeliveries(
            @PathVariable UUID runId,
            @RequestHeader(value = "Internal-Token", required = false) String internalToken) {
        if (internalSecret == null || internalSecret.isBlank() || !internalSecret.equals(internalToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new BaseResponse<>(0, null, "Forbidden"));
        }
        List<SimulationDeliveryStatus> result = deliveryRepository.findBySimulationRunIdOrderByIdAsc(runId)
                .stream()
                .map(delivery -> new SimulationDeliveryStatus(delivery.getId(), delivery.getOrderId(),
                        delivery.getStatus() == null ? "UNKNOWN" : delivery.getStatus().name()))
                .toList();
        return ResponseEntity.ok(new BaseResponse<>(1, result));
    }

    public record SimulationDeliveryStatus(Long deliveryId, Long orderId, String status) { }
}
