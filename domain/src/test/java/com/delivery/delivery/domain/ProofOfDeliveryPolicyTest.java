package com.delivery.delivery.domain;

import com.delivery.delivery.domain.ProofOfDeliveryPolicy.Confirmation;
import com.delivery.delivery.domain.DeliveryAccessPolicy.Viewer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProofOfDeliveryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static final long MB10 = 10L * 1024L * 1024L;

    private static OfferDecisionRejected rejected(Runnable call) {
        return assertThrows(OfferDecisionRejected.class, call::run);
    }

    @Test
    void uploadRequestAcceptsSmallJpegPngWebpOnly() {
        for (String type : new String[] {"image/jpeg", "image/png", "image/webp"}) {
            ProofOfDeliveryPolicy.requireUploadRequest(type, MB10);
        }
        assertEquals("Ảnh bằng chứng phải là JPEG, PNG hoặc WebP và không quá 10 MB",
                rejected(() -> ProofOfDeliveryPolicy.requireUploadRequest("image/gif", 1)).getMessage());
        rejected(() -> ProofOfDeliveryPolicy.requireUploadRequest(null, 1));
        rejected(() -> ProofOfDeliveryPolicy.requireUploadRequest("image/png", 0));
        rejected(() -> ProofOfDeliveryPolicy.requireUploadRequest("image/png", MB10 + 1));
    }

    @Test
    void onlyAssignedShipperAfterPickupWithoutConfirmedProof() {
        ProofOfDeliveryPolicy.requireAssignedShipper(9L, 9L);
        assertEquals(OfferDecisionRejected.Kind.ACCESS_DENIED,
                rejected(() -> ProofOfDeliveryPolicy.requireAssignedShipper(8L, 9L)).kind());
        ProofOfDeliveryPolicy.requireUploadable(DeliveryStatus.PICKED_UP, false);
        ProofOfDeliveryPolicy.requireUploadable(DeliveryStatus.DELIVERING, false);
        assertEquals("Chỉ có thể tạo bằng chứng sau khi đã lấy hàng",
                rejected(() -> ProofOfDeliveryPolicy.requireUploadable(DeliveryStatus.ASSIGNED, false)).getMessage());
        assertEquals("Đơn hàng đã có bằng chứng giao được xác nhận",
                rejected(() -> ProofOfDeliveryPolicy.requireUploadable(DeliveryStatus.DELIVERING, true)).getMessage());
        UUID id = UUID.randomUUID();
        assertEquals("delivery-pod/5/" + id, ProofOfDeliveryPolicy.objectKey(5, id));
    }

    @Test
    void confirmationIsOwnedReplayableAndTimeBounded() {
        assertEquals(Confirmation.CONFIRM, ProofOfDeliveryPolicy.onConfirm(9, 9L,
                DeliveryProofStatus.UPLOAD_PENDING, NOW.plusSeconds(1), NOW));
        assertEquals(Confirmation.EXPIRED, ProofOfDeliveryPolicy.onConfirm(9, 9L,
                DeliveryProofStatus.UPLOAD_PENDING, NOW, NOW));
        assertEquals(Confirmation.REPLAY, ProofOfDeliveryPolicy.onConfirm(9, 9L,
                DeliveryProofStatus.CONFIRMED, NOW, NOW));
        assertEquals("Bằng chứng không thuộc shipper hiện tại", rejected(() -> ProofOfDeliveryPolicy.onConfirm(9, 8L,
                DeliveryProofStatus.UPLOAD_PENDING, NOW, NOW)).getMessage());
        rejected(() -> ProofOfDeliveryPolicy.onConfirm(9, null, DeliveryProofStatus.UPLOAD_PENDING, NOW, NOW));
        assertEquals("Bằng chứng không còn ở trạng thái chờ xác nhận", rejected(() -> ProofOfDeliveryPolicy.onConfirm(
                9, 9L, DeliveryProofStatus.EXPIRED, NOW, NOW)).getMessage());
    }

    @Test
    void storedObjectMustMatchSignedConstraints() {
        ProofOfDeliveryPolicy.requireStoredObject(100, "image/png", 100, "image/png");
        String message = "Đối tượng bằng chứng không đúng ràng buộc đã ký";
        assertEquals(message, rejected(() -> ProofOfDeliveryPolicy.requireStoredObject(100, "image/png", 0, "image/png")).getMessage());
        rejected(() -> ProofOfDeliveryPolicy.requireStoredObject(100, "image/png", 101, "image/png"));
        rejected(() -> ProofOfDeliveryPolicy.requireStoredObject(MB10 + 5, "image/png", MB10 + 1, "image/png"));
        rejected(() -> ProofOfDeliveryPolicy.requireStoredObject(100, "image/png", 100, "image/gif"));
        rejected(() -> ProofOfDeliveryPolicy.requireStoredObject(100, "image/png", 100, "image/jpeg"));
    }

    @Test
    void retentionAndReadability() {
        assertEquals(NOW.plusDays(90), ProofOfDeliveryPolicy.retentionExpiresAt(NOW));
        ProofOfDeliveryPolicy.requireReadable(DeliveryProofStatus.CONFIRMED, NOW.plusSeconds(1), NOW);
        assertEquals("Bằng chứng giao hàng không còn khả dụng", rejected(() -> ProofOfDeliveryPolicy.requireReadable(
                DeliveryProofStatus.CONFIRMED, NOW, NOW)).getMessage());
        rejected(() -> ProofOfDeliveryPolicy.requireReadable(DeliveryProofStatus.CONFIRMED, null, NOW));
        rejected(() -> ProofOfDeliveryPolicy.requireReadable(DeliveryProofStatus.PURGED, NOW.plusDays(1), NOW));
    }

    @Test
    void viewersAreAdminAssignedShipperCustomerOrRestaurantOwner() {
        Runnable noShipperCheck = () -> fail("shipper check only for shipper viewers");
        ProofOfDeliveryPolicy.requireViewer(Viewer.ADMIN, null, null, null, 1L, null, null, noShipperCheck);
        int[] shipperChecks = {0};
        ProofOfDeliveryPolicy.requireViewer(Viewer.SHIPPER, 1L, 1L, null, 1L, null, null, () -> shipperChecks[0]++);
        assertEquals(1, shipperChecks[0]);
        ProofOfDeliveryPolicy.requireViewer(Viewer.CUSTOMER, 7L, 1L, 7L, 2L, null, null, noShipperCheck);
        ProofOfDeliveryPolicy.requireViewer(Viewer.CUSTOMER, 7L, 2L, null, 2L, null, null, noShipperCheck);
        ProofOfDeliveryPolicy.requireViewer(Viewer.RESTAURANT_OWNER, 4L, 1L, null, 2L, 4L, null, noShipperCheck);
        ProofOfDeliveryPolicy.requireViewer(Viewer.RESTAURANT_OWNER, 4L, 3L, null, 2L, null, 3L, noShipperCheck);
        String denied = "Bạn không có quyền xem bằng chứng giao hàng";
        assertEquals(denied, rejected(() -> ProofOfDeliveryPolicy.requireViewer(Viewer.CUSTOMER, 8L, 1L, 7L, 2L,
                null, null, noShipperCheck)).getMessage());
        rejected(() -> ProofOfDeliveryPolicy.requireViewer(Viewer.CUSTOMER, 7L, 3L, null, 2L, null, null, noShipperCheck));
        rejected(() -> ProofOfDeliveryPolicy.requireViewer(Viewer.RESTAURANT_OWNER, 5L, 1L, null, 2L, 4L, null, noShipperCheck));
        rejected(() -> ProofOfDeliveryPolicy.requireViewer(Viewer.RESTAURANT_OWNER, 5L, 1L, null, 2L, null, null, noShipperCheck));
        rejected(() -> ProofOfDeliveryPolicy.requireViewer(Viewer.RESTAURANT_OWNER, 5L, 1L, null, 2L, null, 3L, noShipperCheck));
        rejected(() -> ProofOfDeliveryPolicy.requireViewer(Viewer.OTHER, 1L, 1L, 1L, 1L, 1L, 1L, noShipperCheck));
    }
}
