package com.delivery.delivery_service.service;

import com.delivery.delivery.domain.DeliveryAccessPolicy;
import com.delivery.delivery.domain.OfferDecisionRejected;
import com.delivery.delivery.domain.ProofOfDeliveryPolicy;
import com.delivery.delivery_service.common.constants.RoleConstants;
import com.delivery.delivery_service.dto.request.CreateProofUploadIntentRequest;
import com.delivery.delivery_service.dto.response.ProofAccessResponse;
import com.delivery.delivery_service.dto.response.ProofOfDeliveryResponse;
import com.delivery.delivery_service.dto.response.ProofUploadIntentResponse;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryProofOfDelivery;
import com.delivery.delivery_service.entity.DeliveryProofStatus;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.exception.InvalidStatusException;
import com.delivery.delivery_service.exception.ResourceNotFoundException;
import com.delivery.delivery_service.repository.DeliveryProofOfDeliveryRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private signed-upload/read workflow and 90-day evidence retention boundary. */
@Slf4j
@Service
public class DeliveryProofOfDeliveryService {

    static final long MAX_PROOF_BYTES = ProofOfDeliveryPolicy.MAX_PROOF_BYTES;
    static final int RETENTION_DAYS = ProofOfDeliveryPolicy.RETENTION_DAYS;
    private static final int SWEEP_LIMIT = 100;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryProofOfDeliveryRepository proofRepository;
    private final ProofObjectStorageRegistry storageRegistry;
    private final ShipperIdentityResolver shipperIdentityResolver;

    @Value("${delivery.pod.enabled:false}")
    private boolean podEnabled;

    public DeliveryProofOfDeliveryService(DeliveryRepository deliveryRepository,
                                          DeliveryProofOfDeliveryRepository proofRepository,
                                          ProofObjectStorageRegistry storageRegistry,
                                          ShipperIdentityResolver shipperIdentityResolver) {
        this.deliveryRepository = deliveryRepository;
        this.proofRepository = proofRepository;
        this.storageRegistry = storageRegistry;
        this.shipperIdentityResolver = shipperIdentityResolver;
    }

    @Transactional
    public ProofUploadIntentResponse createUploadIntent(Long deliveryId,
                                                         CreateProofUploadIntentRequest request,
                                                         Long principalId,
                                                         Long legacyUserId,
                                                         String role) {
        requireEnabled();
        validateUploadRequest(request);
        Delivery delivery = findDeliveryForUpdate(deliveryId);
        Long shipperId = requireAssignedShipper(delivery, principalId, legacyUserId, role);
        DeliveryStatus status = delivery.getStatus();
        policy(() -> ProofOfDeliveryPolicy.requireUploadable(domain(status),
                (status == DeliveryStatus.PICKED_UP || status == DeliveryStatus.DELIVERING)
                        && proofRepository.existsByDeliveryIdAndStatus(deliveryId, DeliveryProofStatus.CONFIRMED)));

        ProofObjectStorage storage = storageRegistry.requireConfiguredProvider();
        UUID proofId = UUID.randomUUID();
        String objectKey = ProofOfDeliveryPolicy.objectKey(deliveryId, proofId);
        ProofObjectStorage.SignedUpload signedUpload = storage.createSignedUpload(
                new ProofObjectStorage.UploadRequest(objectKey, request.getContentType(), request.getContentLengthBytes()));
        validateSignedUpload(signedUpload);

        DeliveryProofOfDelivery proof = new DeliveryProofOfDelivery();
        proof.setProofId(proofId);
        proof.setDeliveryId(deliveryId);
        proof.setShipperId(shipperId);
        proof.setStorageProvider(storage.providerId());
        proof.setObjectKey(objectKey);
        proof.setContentType(request.getContentType());
        proof.setDeclaredSizeBytes(request.getContentLengthBytes());
        proof.setStatus(DeliveryProofStatus.UPLOAD_PENDING);
        proof.setUploadExpiresAt(signedUpload.expiresAt());
        proofRepository.save(proof);

        ProofUploadIntentResponse response = new ProofUploadIntentResponse();
        response.setProofId(proofId);
        response.setStatus(proof.getStatus());
        response.setSignedUploadUrl(signedUpload.url());
        response.setRequiredHeaders(signedUpload.requiredHeaders() == null
                ? Map.of() : Map.copyOf(signedUpload.requiredHeaders()));
        response.setUploadExpiresAt(signedUpload.expiresAt());
        response.setMaxContentLengthBytes(MAX_PROOF_BYTES);
        return response;
    }

    @Transactional
    public ProofOfDeliveryResponse confirmUpload(Long deliveryId,
                                                 UUID proofId,
                                                 Long principalId,
                                                 Long legacyUserId,
                                                 String role) {
        requireEnabled();
        Delivery delivery = findDeliveryForUpdate(deliveryId);
        Long shipperId = requireAssignedShipper(delivery, principalId, legacyUserId, role);
        DeliveryProofOfDelivery proof = findProofForUpdate(proofId, deliveryId);
        LocalDateTime now = LocalDateTime.now();
        ProofOfDeliveryPolicy.Confirmation confirmation;
        try {
            confirmation = ProofOfDeliveryPolicy.onConfirm(shipperId, proof.getShipperId(),
                    com.delivery.delivery.domain.DeliveryProofStatus.valueOf(proof.getStatus().name()),
                    proof.getUploadExpiresAt(), now);
        } catch (OfferDecisionRejected rejected) {
            throw failure(rejected);
        }
        if (confirmation == ProofOfDeliveryPolicy.Confirmation.REPLAY) {
            return toProofResponse(proof);
        }
        if (confirmation == ProofOfDeliveryPolicy.Confirmation.EXPIRED) {
            proof.setStatus(DeliveryProofStatus.EXPIRED);
            proofRepository.save(proof);
            throw new InvalidStatusException(ProofOfDeliveryPolicy.UPLOAD_EXPIRED_MESSAGE);
        }

        ProofObjectStorage.StoredObjectMetadata metadata = storageRegistry
                .requireProvider(proof.getStorageProvider())
                .readMetadata(proof.getObjectKey());
        validateStoredObject(proof, metadata);

        proof.setVerifiedSizeBytes(metadata.contentLengthBytes());
        proof.setObjectChecksum(metadata.checksum());
        proof.setContentType(metadata.contentType());
        proof.setStatus(DeliveryProofStatus.CONFIRMED);
        proof.setConfirmedAt(now);
        proof.setRetentionExpiresAt(ProofOfDeliveryPolicy.retentionExpiresAt(now));
        proofRepository.save(proof);
        return toProofResponse(proof);
    }

    @Transactional(readOnly = true)
    public ProofAccessResponse createReadAccess(Long deliveryId,
                                                UUID proofId,
                                                Long principalId,
                                                Long legacyUserId,
                                                String role) {
        requireEnabled();
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin giao hàng với ID: " + deliveryId));
        requireViewer(delivery, principalId, legacyUserId, role);
        DeliveryProofOfDelivery proof = proofRepository.findById(proofId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bằng chứng giao hàng"));
        if (!deliveryId.equals(proof.getDeliveryId())) {
            throw new ResourceNotFoundException("Không tìm thấy bằng chứng giao hàng");
        }
        policy(() -> ProofOfDeliveryPolicy.requireReadable(
                com.delivery.delivery.domain.DeliveryProofStatus.valueOf(proof.getStatus().name()),
                proof.getRetentionExpiresAt(), LocalDateTime.now()));

        ProofObjectStorage.SignedRead signedRead = storageRegistry
                .requireProvider(proof.getStorageProvider())
                .createSignedRead(proof.getObjectKey());
        if (signedRead == null || signedRead.url() == null || signedRead.url().isBlank()
                || signedRead.expiresAt() == null || !signedRead.expiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("POD storage returned an invalid signed read URL");
        }
        ProofAccessResponse response = new ProofAccessResponse();
        response.setProofId(proof.getProofId());
        response.setSignedReadUrl(signedRead.url());
        response.setExpiresAt(signedRead.expiresAt());
        return response;
    }

    /** Removes the private object after the fixed 90-day retention period. */
    @Transactional
    public int purgeRetentionExpiredProofs() {
        if (!podEnabled) return 0;
        List<DeliveryProofOfDelivery> candidates = proofRepository.findRetentionExpiredForUpdate(
                LocalDateTime.now(), PageRequest.of(0, SWEEP_LIMIT));
        int purged = 0;
        for (DeliveryProofOfDelivery proof : candidates) {
            try {
                storageRegistry.requireProvider(proof.getStorageProvider()).deleteObject(proof.getObjectKey());
                proof.setStatus(DeliveryProofStatus.PURGED);
                proof.setPurgedAt(LocalDateTime.now());
                proofRepository.save(proof);
                purged++;
            } catch (RuntimeException failure) {
                // Keep the confirmed record so the next bounded sweep retries;
                // never claim retention has completed without object deletion.
                log.warn("Unable to purge POD proof {} for delivery {}", proof.getProofId(), proof.getDeliveryId(), failure);
            }
        }
        return purged;
    }

    private void requireEnabled() {
        if (!podEnabled) {
            throw new InvalidStatusException("Bằng chứng giao hàng chưa được bật");
        }
    }

    private Delivery findDeliveryForUpdate(Long deliveryId) {
        if (deliveryId == null || deliveryId <= 0) {
            throw new InvalidStatusException("Delivery ID is required");
        }
        return deliveryRepository.findByIdForUpdate(deliveryId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin giao hàng với ID: " + deliveryId));
    }

    private DeliveryProofOfDelivery findProofForUpdate(UUID proofId, Long deliveryId) {
        if (proofId == null) throw new InvalidStatusException("Proof ID is required");
        DeliveryProofOfDelivery proof = proofRepository.findByIdForUpdate(proofId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bằng chứng giao hàng"));
        if (!deliveryId.equals(proof.getDeliveryId())) {
            throw new ResourceNotFoundException("Không tìm thấy bằng chứng giao hàng");
        }
        return proof;
    }

    private Long requireAssignedShipper(Delivery delivery, Long principalId, Long legacyUserId, String role) {
        Long shipperId = shipperIdentityResolver.resolveShipperId(principalId, legacyUserId, role);
        policy(() -> ProofOfDeliveryPolicy.requireAssignedShipper(shipperId, delivery.getShipperId()));
        return shipperId;
    }

    private void requireViewer(Delivery delivery, Long principalId, Long legacyUserId, String role) {
        DeliveryAccessPolicy.Viewer viewer = RoleConstants.ADMIN.equals(role) ? DeliveryAccessPolicy.Viewer.ADMIN
                : RoleConstants.SHIPPER.equals(role) ? DeliveryAccessPolicy.Viewer.SHIPPER
                : RoleConstants.USER.equals(role) ? DeliveryAccessPolicy.Viewer.CUSTOMER
                : RoleConstants.RESTAURANT_OWNER.equals(role) ? DeliveryAccessPolicy.Viewer.RESTAURANT_OWNER
                : DeliveryAccessPolicy.Viewer.OTHER;
        policy(() -> ProofOfDeliveryPolicy.requireViewer(viewer, principalId, legacyUserId,
                delivery.getCustomerPrincipalId(), delivery.getCreatorId(),
                delivery.getRestaurantOwnerPrincipalId(), delivery.getRestaurantOwnerId(),
                () -> requireAssignedShipper(delivery, principalId, legacyUserId, role)));
    }

    private void validateUploadRequest(CreateProofUploadIntentRequest request) {
        if (request == null) {
            throw new InvalidStatusException("Ảnh bằng chứng phải là JPEG, PNG hoặc WebP và không quá 10 MB");
        }
        policy(() -> ProofOfDeliveryPolicy.requireUploadRequest(request.getContentType(),
                request.getContentLengthBytes()));
    }

    private void validateSignedUpload(ProofObjectStorage.SignedUpload signedUpload) {
        if (signedUpload == null || signedUpload.url() == null || signedUpload.url().isBlank()
                || signedUpload.expiresAt() == null || !signedUpload.expiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("POD storage returned an invalid signed upload URL");
        }
    }

    private void validateStoredObject(DeliveryProofOfDelivery proof,
                                      ProofObjectStorage.StoredObjectMetadata metadata) {
        if (metadata == null) {
            throw new InvalidStatusException("Đối tượng bằng chứng không đúng ràng buộc đã ký");
        }
        policy(() -> ProofOfDeliveryPolicy.requireStoredObject(proof.getDeclaredSizeBytes(), proof.getContentType(),
                metadata.contentLengthBytes(), metadata.contentType()));
    }

    /** Runs a domain rule and maps its refusal to the unchanged HTTP-facing exceptions. */
    private static void policy(Runnable rule) {
        try {
            rule.run();
        } catch (OfferDecisionRejected rejected) {
            throw failure(rejected);
        }
    }

    private static RuntimeException failure(OfferDecisionRejected rejected) {
        return rejected.kind() == OfferDecisionRejected.Kind.ACCESS_DENIED
                ? new AccessDeniedException(rejected.getMessage())
                : new InvalidStatusException(rejected.getMessage());
    }

    private static com.delivery.delivery.domain.DeliveryStatus domain(DeliveryStatus status) {
        return com.delivery.delivery.domain.DeliveryStatus.valueOf(status.name());
    }

    private ProofOfDeliveryResponse toProofResponse(DeliveryProofOfDelivery proof) {
        ProofOfDeliveryResponse response = new ProofOfDeliveryResponse();
        response.setProofId(proof.getProofId());
        response.setStatus(proof.getStatus());
        response.setContentType(proof.getContentType());
        response.setSizeBytes(proof.getVerifiedSizeBytes());
        response.setConfirmedAt(proof.getConfirmedAt());
        response.setRetentionExpiresAt(proof.getRetentionExpiresAt());
        return response;
    }
}
