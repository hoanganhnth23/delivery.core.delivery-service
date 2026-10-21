package com.delivery.delivery_service.service;

import com.delivery.delivery_service.exception.ProofStorageUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Selects an explicitly configured private POD storage adapter. */
@Service
public class ProofObjectStorageRegistry {

    private final Map<String, ProofObjectStorage> providers = new LinkedHashMap<>();

    @Value("${delivery.pod.storage-provider:}")
    private String configuredProvider;

    public ProofObjectStorageRegistry(Collection<ProofObjectStorage> providers) {
        if (providers == null) return;
        for (ProofObjectStorage provider : providers) {
            if (provider == null || provider.providerId() == null || provider.providerId().isBlank()) {
                throw new IllegalStateException("POD storage provider ID is required");
            }
            String key = normalize(provider.providerId());
            if (this.providers.putIfAbsent(key, provider) != null) {
                throw new IllegalStateException("Duplicate POD storage provider: " + provider.providerId());
            }
        }
    }

    public ProofObjectStorage requireConfiguredProvider() {
        if (configuredProvider == null || configuredProvider.isBlank()) {
            throw new ProofStorageUnavailableException("POD private object storage is not configured");
        }
        return requireProvider(configuredProvider);
    }

    public ProofObjectStorage requireProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new ProofStorageUnavailableException("POD storage provider is missing");
        }
        ProofObjectStorage provider = providers.get(normalize(providerId));
        if (provider == null) {
            throw new ProofStorageUnavailableException("POD storage provider is unavailable");
        }
        return provider;
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
