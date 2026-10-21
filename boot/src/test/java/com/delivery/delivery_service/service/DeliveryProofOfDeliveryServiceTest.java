package com.delivery.delivery_service.service;

import com.delivery.delivery_service.dto.request.CreateProofUploadIntentRequest;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryProofOfDelivery;
import com.delivery.delivery_service.entity.DeliveryProofStatus;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.exception.InvalidStatusException;
import com.delivery.delivery_service.exception.ProofStorageUnavailableException;
import com.delivery.delivery_service.repository.DeliveryProofOfDeliveryRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import com.delivery.delivery_service.repository.ShipperIdentityProjectionRepository;
import com.delivery.delivery_service.entity.ShipperIdentityProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryProofOfDeliveryServiceTest {
    @Mock DeliveryRepository deliveryRepository;
    @Mock DeliveryProofOfDeliveryRepository proofRepository;
    @Mock ProofObjectStorageRegistry storageRegistry;
    @Mock ProofObjectStorage storage;
    @Mock ShipperIdentityProjectionRepository projections;
    private ShipperIdentityResolver identityResolver;

    private DeliveryProofOfDeliveryService service;
    private Delivery delivery;

    @BeforeEach
    void setUp() {
        delivery = delivery();
        ShipperIdentityProjection mapping = new ShipperIdentityProjection();
        mapping.setPrincipalId(1007L);
        mapping.setLegacyUserId(107L);
        mapping.setShipperId(7L);
        identityResolver = ShipperIdentityResolver.compatibility(projections, null, false);
        lenient().when(projections.findById(1007L)).thenReturn(Optional.of(mapping));
        service = new DeliveryProofOfDeliveryService(deliveryRepository, proofRepository, storageRegistry, identityResolver);
        ReflectionTestUtils.setField(service, "podEnabled", true);
        lenient().when(deliveryRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(delivery));
        lenient().when(deliveryRepository.findById(7L)).thenReturn(Optional.of(delivery));
        lenient().when(storageRegistry.requireConfiguredProvider()).thenReturn(storage);
        lenient().when(storage.providerId()).thenReturn("test-private");
    }

    @Test
    void createsPrivateSignedUploadIntentWithTenMbCap() {
        CreateProofUploadIntentRequest request = request("image/jpeg", 2_000_000L);
        LocalDateTime expires = LocalDateTime.now().plusMinutes(5);
        when(storage.createSignedUpload(any())).thenReturn(
                new ProofObjectStorage.SignedUpload("https://private/upload", Map.of("x-test", "1"), expires));
        when(proofRepository.existsByDeliveryIdAndStatus(7L, DeliveryProofStatus.CONFIRMED)).thenReturn(false);

        var response = service.createUploadIntent(7L, request, 1007L, 107L, "SHIPPER");

        assertThat(response.getSignedUploadUrl()).isEqualTo("https://private/upload");
        assertThat(response.getMaxContentLengthBytes()).isEqualTo(10L * 1024L * 1024L);
        ArgumentCaptor<DeliveryProofOfDelivery> proof = ArgumentCaptor.forClass(DeliveryProofOfDelivery.class);
        verify(proofRepository).save(proof.capture());
        assertThat(proof.getValue().getStatus()).isEqualTo(DeliveryProofStatus.UPLOAD_PENDING);
        assertThat(proof.getValue().getDeclaredSizeBytes()).isEqualTo(2_000_000L);
        assertThat(proof.getValue().getObjectKey()).startsWith("delivery-pod/7/");
    }

    @Test
    void rejectsOversizedOrUnsupportedProofBeforeStorageAccess() {
        assertThatThrownBy(() -> service.createUploadIntent(7L, request("image/gif", 1), 1007L, 107L, "SHIPPER"))
                .isInstanceOf(InvalidStatusException.class);
        assertThatThrownBy(() -> service.createUploadIntent(7L,
                request("image/jpeg", 10L * 1024L * 1024L + 1), 1007L, 107L, "SHIPPER"))
                .isInstanceOf(InvalidStatusException.class);
        verifyNoInteractions(storageRegistry, deliveryRepository, proofRepository);
    }

    @Test
    void confirmsOnlyObjectMatchingSignedMetadataAndRetainsForNinetyDays() {
        UUID proofId = UUID.randomUUID();
        DeliveryProofOfDelivery proof = pendingProof(proofId);
        LocalDateTime now = LocalDateTime.now();
        when(proofRepository.findByIdForUpdate(proofId)).thenReturn(Optional.of(proof));
        when(storageRegistry.requireProvider("test-private")).thenReturn(storage);
        when(storage.readMetadata(proof.getObjectKey())).thenReturn(
                new ProofObjectStorage.StoredObjectMetadata(2_000_000L, "image/jpeg", "sha256:test"));

        var response = service.confirmUpload(7L, proofId, 1007L, 107L, "SHIPPER");

        assertThat(response.getStatus()).isEqualTo(DeliveryProofStatus.CONFIRMED);
        assertThat(proof.getVerifiedSizeBytes()).isEqualTo(2_000_000L);
        assertThat(proof.getRetentionExpiresAt()).isAfter(now.plusDays(89));
        verify(proofRepository).save(proof);
    }

    @Test
    void rejectsStorageWhenNoExplicitPrivateProviderIsConfigured() {
        when(storageRegistry.requireConfiguredProvider()).thenThrow(
                new ProofStorageUnavailableException("not configured"));

        assertThatThrownBy(() -> service.createUploadIntent(7L, request("image/png", 100), 1007L, 107L, "SHIPPER"))
                .isInstanceOf(ProofStorageUnavailableException.class);
        verify(proofRepository, never()).save(any());
    }

    @Test
    void readAccessIsSignedAndDoesNotExposeObjectKey() {
        UUID proofId = UUID.randomUUID();
        DeliveryProofOfDelivery proof = pendingProof(proofId);
        proof.setStatus(DeliveryProofStatus.CONFIRMED);
        proof.setConfirmedAt(LocalDateTime.now().minusMinutes(1));
        proof.setRetentionExpiresAt(LocalDateTime.now().plusDays(10));
        when(proofRepository.findById(proofId)).thenReturn(Optional.of(proof));
        LocalDateTime expires = LocalDateTime.now().plusMinutes(2);
        when(storageRegistry.requireProvider("test-private")).thenReturn(storage);
        when(storage.createSignedRead(proof.getObjectKey())).thenReturn(
                new ProofObjectStorage.SignedRead("https://private/read", expires));

        var response = service.createReadAccess(7L, proofId, 1007L, 107L, "SHIPPER");

        assertThat(response.getSignedReadUrl()).isEqualTo("https://private/read");
        assertThat(response.getProofId()).isEqualTo(proofId);
        assertThat(response.getClass().getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
                .doesNotContain("objectKey");
    }

    private CreateProofUploadIntentRequest request(String type, long size) {
        CreateProofUploadIntentRequest request = new CreateProofUploadIntentRequest();
        request.setContentType(type);
        request.setContentLengthBytes(size);
        return request;
    }

    private Delivery delivery() {
        Delivery result = new Delivery();
        result.setId(7L);
        result.setOrderId(70L);
        result.setShipperId(7L);
        result.setCreatorId(107L);
        result.setCustomerPrincipalId(1007L);
        result.setRestaurantId(17L);
        result.setRestaurantOwnerId(117L);
        result.setStatus(DeliveryStatus.DELIVERING);
        return result;
    }

    private DeliveryProofOfDelivery pendingProof(UUID proofId) {
        DeliveryProofOfDelivery proof = new DeliveryProofOfDelivery();
        proof.setProofId(proofId);
        proof.setDeliveryId(7L);
        proof.setShipperId(7L);
        proof.setStorageProvider("test-private");
        proof.setObjectKey("delivery-pod/7/" + proofId);
        proof.setContentType("image/jpeg");
        proof.setDeclaredSizeBytes(2_000_000L);
        proof.setUploadExpiresAt(LocalDateTime.now().plusMinutes(5));
        proof.setStatus(DeliveryProofStatus.UPLOAD_PENDING);
        return proof;
    }
}
