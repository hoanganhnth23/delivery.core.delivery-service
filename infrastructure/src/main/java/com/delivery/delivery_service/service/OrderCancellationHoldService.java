package com.delivery.delivery_service.service;

import com.delivery.delivery.domain.CancellationPolicy;
import com.delivery.delivery.domain.OfferDecisionRejected;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.repository.DeliveryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Delivery is the authority on whether an order may still be cancelled once a
 * shipper may be involved: Order's copy of the delivery status can lag behind a
 * pickup. An admitted request holds the delivery (pickup refused) until the
 * usual order.cancelled event cancels it, or the hold expires if Order never
 * committed its cancellation.
 */
@Service
public class OrderCancellationHoldService {

    public record Decision(boolean allowed, String deliveryStatus, LocalDateTime holdUntil, String message) { }

    private final DeliveryRepository deliveryRepository;
    private final long holdSeconds;
    private final Clock clock;

    @Autowired
    public OrderCancellationHoldService(DeliveryRepository deliveryRepository,
                                        @Value("${delivery.order-cancel-hold-seconds:120}") long holdSeconds) {
        this(deliveryRepository, holdSeconds, Clock.systemDefaultZone());
    }

    OrderCancellationHoldService(DeliveryRepository deliveryRepository, long holdSeconds, Clock clock) {
        if (holdSeconds < 1 || holdSeconds > 900) {
            throw new IllegalArgumentException("delivery.order-cancel-hold-seconds must be between 1 and 900");
        }
        this.deliveryRepository = deliveryRepository;
        this.holdSeconds = holdSeconds;
        this.clock = clock;
    }

    @Transactional
    public Decision requestHold(Long orderId) {
        if (orderId == null || orderId <= 0) throw new IllegalArgumentException("orderId is required");
        Delivery delivery = deliveryRepository.findByOrderIdForUpdate(orderId).orElse(null);
        CancellationPolicy.CancelRequest request;
        try {
            request = CancellationPolicy.onOrderCancelRequest(delivery != null,
                    delivery == null ? null : com.delivery.delivery.domain.DeliveryStatus.valueOf(delivery.getStatus().name()));
        } catch (OfferDecisionRejected refused) {
            return new Decision(false, delivery.getStatus().name(), null, refused.getMessage());
        }
        return switch (request) {
            case NO_DELIVERY -> new Decision(true, null, null, "No delivery yet");
            case ALREADY_CANCELLED -> new Decision(true, delivery.getStatus().name(), null, "Delivery already cancelled");
            case HOLD -> {
                LocalDateTime until = LocalDateTime.now(clock).plusSeconds(holdSeconds);
                delivery.setCancelHoldUntil(until);
                deliveryRepository.save(delivery);
                yield new Decision(true, delivery.getStatus().name(), until, "Pickup held for cancellation");
            }
        };
    }
}
