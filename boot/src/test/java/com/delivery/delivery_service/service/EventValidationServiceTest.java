package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.event.OrderCreatedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class EventValidationServiceTest {
    private final EventValidationService service = new EventValidationService();

    @Test
    void acceptsLegacyAndExplicitPromotionAmounts() {
        OrderCreatedEvent event = validEvent();
        assertValid(event);
        event.setItemDiscount(BigDecimal.TEN);
        event.setCustomerShippingFee(BigDecimal.ONE);
        event.setTotalPrice(new BigDecimal("91"));
        assertValid(event);
        event.setTotalPrice(new BigDecimal("92"));
        assertInvalid(event, "Total price không khớp subtotal - item discount + customer shipping");
    }

    @Test
    void rejectsNullEventAndAggregatesMissingRequiredFields() {
        assertInvalid(null, "OrderCreatedEvent không được null");
        OrderCreatedEvent event = new OrderCreatedEvent();
        var result = service.validateOrderCreatedEvent(event);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).contains("Event ID không được null", "Order ID", "User ID",
                "Restaurant ID", "Restaurant owner ID", "Status", "Subtotal price", "Total price",
                "Shipping fee", "Discount amount", "Payment method", "Delivery address", "Restaurant address",
                "Pickup/delivery coordinates").contains("; ");
    }

    @Test
    void rejectsZeroAndNegativeIdentities() {
        List<Consumer<OrderCreatedEvent>> setters = List.of(
                event -> event.setOrderId(0L), event -> event.setOrderId(-1L),
                event -> event.setUserId(0L), event -> event.setUserId(-1L),
                event -> event.setRestaurantId(0L), event -> event.setRestaurantId(-1L),
                event -> event.setCreatorId(0L), event -> event.setCreatorId(-1L));
        for (Consumer<OrderCreatedEvent> setter : setters) {
            OrderCreatedEvent event = validEvent();
            setter.accept(event);
            assertInvalid(event, "ID không được null hoặc <= 0");
        }
    }

    @Test
    void rejectsBlankStatusShortAddressesAndNonCodPayments() {
        for (String value : List.of("", "  ")) {
            OrderCreatedEvent event = validEvent();
            event.setStatus(value);
            assertInvalid(event, "Status không được null hoặc rỗng");
        }
        OrderCreatedEvent event = validEvent();
        event.setDeliveryAddress(" 123456789 ");
        event.setRestaurantAddress(" 123456789 ");
        event.setPaymentMethod("CARD");
        assertInvalid(event, "Delivery address", "Restaurant address", "Payment method phải là COD trong MVP");
    }

    @Test
    void rejectsNonPositivePricesAndNegativeDiscounts() {
        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ZERO, BigDecimal.ONE.negate())) {
            OrderCreatedEvent event = validEvent();
            event.setSubtotalPrice(amount);
            assertInvalid(event, "Subtotal price phải lớn hơn 0");
            event = validEvent();
            event.setShippingFee(amount);
            assertInvalid(event, "Shipping fee phải lớn hơn 0");
            event = validEvent();
            event.setTotalPrice(amount);
            assertInvalid(event, "Total price phải lớn hơn 0");
        }
        for (BigDecimal amount : Arrays.asList(null, BigDecimal.ONE.negate())) {
            OrderCreatedEvent event = validEvent();
            event.setDiscountAmount(amount);
            assertInvalid(event, "Discount amount không được null hoặc âm");
        }
    }

    @Test
    void validatesEachCoordinateIncludingNonFiniteValuesAndInclusiveBoundaries() {
        List<java.util.function.BiConsumer<OrderCreatedEvent, Double>> setters = List.of(
                OrderCreatedEvent::setDeliveryLat, OrderCreatedEvent::setPickupLat,
                OrderCreatedEvent::setDeliveryLng, OrderCreatedEvent::setPickupLng);
        for (int index = 0; index < setters.size(); index++) {
            double minimum = index < 2 ? 8.0 : 102.0;
            double maximum = index < 2 ? 24.0 : 110.0;
            for (Double coordinate : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, minimum - 0.01, maximum + 0.01)) {
                OrderCreatedEvent event = validEvent();
                setters.get(index).accept(event, coordinate);
                assertInvalid(event, "Pickup/delivery coordinates");
            }
            for (double boundary : List.of(minimum, maximum)) {
                OrderCreatedEvent event = validEvent();
                setters.get(index).accept(event, boundary);
                assertValid(event);
            }
        }
    }

    private void assertValid(OrderCreatedEvent event) {
        var result = service.validateOrderCreatedEvent(event);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrorMessage()).isNull();
    }

    private void assertInvalid(OrderCreatedEvent event, String... messages) {
        var result = service.validateOrderCreatedEvent(event);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).contains(messages);
    }

    private OrderCreatedEvent validEvent() {
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setEventId(UUID.randomUUID());
        event.setOrderId(1L);
        event.setUserId(1L);
        event.setRestaurantId(1L);
        event.setCreatorId(1L);
        event.setStatus("CREATED");
        event.setSubtotalPrice(new BigDecimal("100"));
        event.setShippingFee(BigDecimal.TEN);
        event.setDiscountAmount(BigDecimal.ZERO);
        event.setTotalPrice(new BigDecimal("110"));
        event.setPaymentMethod("COD");
        event.setDeliveryAddress("1234567890");
        event.setRestaurantAddress("1234567890");
        event.setDeliveryLat(16.0);
        event.setPickupLat(16.0);
        event.setDeliveryLng(106.0);
        event.setPickupLng(106.0);
        return event;
    }
}
