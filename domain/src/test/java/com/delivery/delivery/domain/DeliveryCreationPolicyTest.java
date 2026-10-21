package com.delivery.delivery.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.delivery.delivery.domain.DeliveryCreationPolicy.*;

class DeliveryCreationPolicyTest {
    private Create valid() {
        return new Create(1L, UUID.randomUUID(), 2L, 3L, 4L, "pickup", 10d, 100d,
                "dropoff", 11d, 101d, new BigDecimal("20"), new BigDecimal("120"), null, null, "COD");
    }
    private Create with(Create value, int index, Object replacement) throws Exception {
        var components = Create.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i=0;i<components.length;i++) {
            values[i] = components[i].getAccessor().invoke(value);
            types[i] = components[i].getType();
        }
        values[index] = replacement;
        return Create.class.getDeclaredConstructor(types).newInstance(values);
    }
    @Test void identityAdmissionPreservesOrderAndMessages() throws Exception {
        assertEquals("OrderCreatedEvent is required", assertThrows(OfferDecisionRejected.class, () -> requireEvent(null)).getMessage());
        Create value = valid();
        requireEvent(value);
        String[] messages = {"Create-delivery orderId must be positive", "Create-delivery eventId is required",
                "Create-delivery userId must be positive", "Create-delivery restaurantId must be positive", "Create-delivery creatorId must be positive"};
        for(int i=0;i<5;i++) {
            Create missing = with(value,i,null);
            assertEquals(messages[i],assertThrows(OfferDecisionRejected.class,()->requireEvent(missing)).getMessage());
            if(i != 1) for(Long invalid : new Long[]{0L,-1L}) {
                Create bad = with(value,i,invalid);
                assertEquals(messages[i],assertThrows(OfferDecisionRejected.class,()->requireEvent(bad)).getMessage());
            }
        }
        Create allMissing = with(with(with(with(with(value,0,null),1,null),2,null),3,null),4,null);
        Create initialMissing = allMissing;
        assertEquals(messages[1],assertThrows(OfferDecisionRejected.class,()->requireEvent(initialMissing)).getMessage());
        allMissing = with(allMissing,1,value.eventId());
        for(int i : new int[]{0,2,3,4}) {
            Create stepMissing = allMissing;
            assertEquals(messages[i],assertThrows(OfferDecisionRejected.class,()->requireEvent(stepMissing)).getMessage());
            allMissing = with(allMissing,i,(long)i+1);
        }
    }
    @Test void replayChecksEachComparedFactButNotOrderIdOrAmountScale() throws Exception {
        Create value = valid();
        requireReplay(9L,value,value);
        requireReplay(9L,value,with(value,0,999L)); // lookup owns order correlation
        requireReplay(9L,value,with(with(value,11,new BigDecimal("20.00")),12,new BigDecimal("120.00")));
        requireReplay(9L,value,with(value,13,new BigDecimal("20.00")));
        for(int i=1;i<16;i++) {
            Object current=Create.class.getRecordComponents()[i].getAccessor().invoke(value);
            Object replacement=current == null ? (i == 13 ? new BigDecimal("21") : UUID.randomUUID()) : null;
            Create contradictory=with(value,i,replacement);
            var rejection=assertThrows(OfferDecisionRejected.class,()->requireReplay(9L,value,contradictory));
            assertThrows(OfferDecisionRejected.class,()->requireReplay(9L,contradictory,value));
            if (i == 11 || i == 12) {
                assertThrows(OfferDecisionRejected.class,()->requireReplay(9L,contradictory,contradictory));
            } else {
                requireReplay(9L,contradictory,contradictory);
            }
            assertEquals(OfferDecisionRejected.Kind.INVALID_STATUS,rejection.kind());
            assertEquals("Create-delivery replay conflicts with existing delivery 9 for order 1",rejection.getMessage());
            if(current != null) {
                Object different=current instanceof Long ? 999L : current instanceof Double ? 99d : current instanceof BigDecimal ? new BigDecimal("999") : current instanceof UUID ? UUID.randomUUID() : "different";
                Create changed=with(value,i,different);
                assertThrows(OfferDecisionRejected.class,()->requireReplay(9L,value,changed));
            }
        }
        Create missing=with(value,11,null);
        Create missingFee=missing;
        assertThrows(OfferDecisionRejected.class,()->requireReplay(9L,missingFee,missingFee));
        missing=with(value,12,null);
        Create finalMissing=missing;
        assertThrows(OfferDecisionRejected.class,()->requireReplay(9L,finalMissing,finalMissing));
        Create gross=with(value,13,new BigDecimal("30"));
        requireReplay(9L,gross,gross);
        assertThrows(OfferDecisionRejected.class,()->requireReplay(9L,gross,value));
    }
    @Test void snapshotDefaultsPreserveNullsAndExplicitValues() {
        var fee=new BigDecimal("20");var discount=new BigDecimal("3");
        assertEquals(new Money(fee,fee,discount,discount),money(fee,null,null,discount,null,null));
        assertEquals(new Money(discount,discount,fee,fee),money(fee,discount,discount,discount,fee,fee));
        assertEquals(new Money(null,null,null,null),money(null,null,null,null,null,null));
        assertEquals(DeliveryStatus.FINDING_SHIPPER,initialStatus());
    }
}
