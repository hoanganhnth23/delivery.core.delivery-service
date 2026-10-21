package com.delivery.delivery.domain;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.ACCESS_DENIED;
import static com.delivery.delivery.domain.OfferDecisionRejected.Kind.INVALID_STATUS;

/**
 * Proof-of-delivery evidence: the assigned shipper uploads one image after
 * pickup through a signed URL, confirms it before the URL expires, and the
 * private object is retained for a fixed 90 days for permitted viewers.
 */
public final class ProofOfDeliveryPolicy {

    public static final long MAX_PROOF_BYTES = 10L * 1024L * 1024L;
    public static final int RETENTION_DAYS = 90;

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private ProofOfDeliveryPolicy() {
    }

    public static boolean isAllowedImageType(String contentType) {
        return contentType != null && ALLOWED_TYPES.contains(contentType);
    }

    public static void requireUploadRequest(String contentType, long contentLengthBytes) {
        if (!isAllowedImageType(contentType) || contentLengthBytes <= 0 || contentLengthBytes > MAX_PROOF_BYTES) {
            throw new OfferDecisionRejected(INVALID_STATUS,
                    "Ảnh bằng chứng phải là JPEG, PNG hoặc WebP và không quá 10 MB");
        }
    }

    public static void requireAssignedShipper(Long actorShipperId, Long assignedShipperId) {
        if (!Objects.equals(actorShipperId, assignedShipperId)) {
            throw new OfferDecisionRejected(ACCESS_DENIED, "Chỉ shipper được phân công mới có thể thao tác bằng chứng");
        }
    }

    public static void requireUploadable(DeliveryStatus status, boolean confirmedProofExists) {
        if (status != DeliveryStatus.PICKED_UP && status != DeliveryStatus.DELIVERING) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Chỉ có thể tạo bằng chứng sau khi đã lấy hàng");
        }
        if (confirmedProofExists) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Đơn hàng đã có bằng chứng giao được xác nhận");
        }
    }

    public static String objectKey(long deliveryId, UUID proofId) {
        return "delivery-pod/" + deliveryId + "/" + proofId;
    }

    public enum Confirmation { REPLAY, EXPIRED, CONFIRM }

    public static Confirmation onConfirm(long actorShipperId, Long proofShipperId, DeliveryProofStatus status,
                                         LocalDateTime uploadExpiresAt, LocalDateTime now) {
        if (proofShipperId == null || actorShipperId != proofShipperId) {
            throw new OfferDecisionRejected(ACCESS_DENIED, "Bằng chứng không thuộc shipper hiện tại");
        }
        if (status == DeliveryProofStatus.CONFIRMED) return Confirmation.REPLAY;
        if (status != DeliveryProofStatus.UPLOAD_PENDING) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Bằng chứng không còn ở trạng thái chờ xác nhận");
        }
        return uploadExpiresAt.isAfter(now) ? Confirmation.CONFIRM : Confirmation.EXPIRED;
    }

    public static final String UPLOAD_EXPIRED_MESSAGE = "URL tải bằng chứng đã hết hạn";

    /** The stored object must match the signed constraints and the declared upload. */
    public static void requireStoredObject(long declaredSizeBytes, String declaredContentType,
                                           long storedSizeBytes, String storedContentType) {
        if (storedSizeBytes <= 0 || storedSizeBytes > MAX_PROOF_BYTES || storedSizeBytes > declaredSizeBytes
                || !isAllowedImageType(storedContentType) || !declaredContentType.equals(storedContentType)) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Đối tượng bằng chứng không đúng ràng buộc đã ký");
        }
    }

    public static LocalDateTime retentionExpiresAt(LocalDateTime confirmedAt) {
        return confirmedAt.plusDays(RETENTION_DAYS);
    }

    public static void requireReadable(DeliveryProofStatus status, LocalDateTime retentionExpiresAt, LocalDateTime now) {
        if (status != DeliveryProofStatus.CONFIRMED || retentionExpiresAt == null || !retentionExpiresAt.isAfter(now)) {
            throw new OfferDecisionRejected(INVALID_STATUS, "Bằng chứng giao hàng không còn khả dụng");
        }
    }

    public static void requireViewer(DeliveryAccessPolicy.Viewer viewer, Long principalId, Long legacyUserId,
                                     Long customerPrincipalId, Long creatorId,
                                     Long ownerPrincipalId, Long ownerLegacyId, Runnable assignedShipperCheck) {
        DeliveryAccessPolicy.requireViewer(viewer, principalId, legacyUserId, customerPrincipalId, creatorId,
                ownerPrincipalId, ownerLegacyId, assignedShipperCheck, "Bạn không có quyền xem bằng chứng giao hàng");
    }
}
