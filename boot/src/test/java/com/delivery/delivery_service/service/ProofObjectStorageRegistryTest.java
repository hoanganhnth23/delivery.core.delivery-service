package com.delivery.delivery_service.service;

import com.delivery.delivery_service.exception.ProofStorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProofObjectStorageRegistryTest {
    @Test
    void normalizesConfiguredAndRequestedProviderNames() {
        ProofObjectStorage storage = provider(" Private-S3 ");
        ProofObjectStorageRegistry registry = new ProofObjectStorageRegistry(List.of(storage));
        ReflectionTestUtils.setField(registry, "configuredProvider", " PRIVATE-S3 ");

        assertThat(registry.requireConfiguredProvider()).isSameAs(storage);
        assertThat(registry.requireProvider("private-s3")).isSameAs(storage);
    }

    @Test
    void rejectsMissingAndUnavailableProviders() {
        ProofObjectStorageRegistry registry = new ProofObjectStorageRegistry(null);
        assertThatThrownBy(registry::requireConfiguredProvider)
                .isInstanceOf(ProofStorageUnavailableException.class)
                .hasMessage("POD private object storage is not configured");
        ReflectionTestUtils.setField(registry, "configuredProvider", "  ");
        assertThatThrownBy(registry::requireConfiguredProvider)
                .isInstanceOf(ProofStorageUnavailableException.class)
                .hasMessage("POD private object storage is not configured");
        for (String providerId : Arrays.asList(null, "", "  ")) {
            assertThatThrownBy(() -> registry.requireProvider(providerId))
                    .isInstanceOf(ProofStorageUnavailableException.class)
                    .hasMessage("POD storage provider is missing");
        }
        ReflectionTestUtils.setField(registry, "configuredProvider", "absent");
        assertThatThrownBy(registry::requireConfiguredProvider)
                .isInstanceOf(ProofStorageUnavailableException.class)
                .hasMessage("POD storage provider is unavailable");
        assertThatThrownBy(() -> new ProofObjectStorageRegistry(List.of()).requireProvider("absent"))
                .isInstanceOf(ProofStorageUnavailableException.class)
                .hasMessage("POD storage provider is unavailable");
    }

    @Test
    void rejectsInvalidAndDuplicateAdapterIdentities() {
        assertThatThrownBy(() -> new ProofObjectStorageRegistry(Arrays.asList((ProofObjectStorage) null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("POD storage provider ID is required");
        for (String providerId : Arrays.asList(null, "", "  ")) {
            ProofObjectStorage storage = provider(providerId);
            assertThatThrownBy(() -> new ProofObjectStorageRegistry(List.of(storage)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("POD storage provider ID is required");
        }
        assertThatThrownBy(() -> new ProofObjectStorageRegistry(List.of(provider("s3"), provider(" S3 "))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Duplicate POD storage provider:  S3 ");
    }

    private ProofObjectStorage provider(String providerId) {
        ProofObjectStorage storage = mock(ProofObjectStorage.class);
        when(storage.providerId()).thenReturn(providerId);
        return storage;
    }
}
