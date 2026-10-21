package com.delivery.delivery_service.service;

import java.time.LocalDateTime;
import java.util.Map;

/** Provider-neutral private object-storage port for POD artifacts. */
public interface ProofObjectStorage {

    String providerId();

    SignedUpload createSignedUpload(UploadRequest request);

    StoredObjectMetadata readMetadata(String objectKey);

    SignedRead createSignedRead(String objectKey);

    void deleteObject(String objectKey);

    record UploadRequest(String objectKey, String contentType, long maxContentLengthBytes) { }

    record SignedUpload(String url, Map<String, String> requiredHeaders, LocalDateTime expiresAt) { }

    record StoredObjectMetadata(long contentLengthBytes, String contentType, String checksum) { }

    record SignedRead(String url, LocalDateTime expiresAt) { }
}
