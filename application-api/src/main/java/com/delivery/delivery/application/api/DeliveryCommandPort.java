package com.delivery.delivery.application.api;

import java.util.UUID;

/** Inbound delivery commands. The HTTP adapter translates requests into these records. */
public interface DeliveryCommandPort {
    Object acceptDelivery(AcceptDeliveryCommand command);
    Object cancelAssignment(CancelAssignmentCommand command);
    Object acceptBatch(AcceptBatchCommand command);
    void rejectBatch(RejectBatchCommand command);
    Object updateStatus(UpdateStatusCommand command);
    Object createProofUploadIntent(CreateProofUploadIntentCommand command);
    Object confirmProofUpload(ConfirmProofUploadCommand command);
    Object reportFailure(ReportFailureCommand command);
    Object useRetry(ExceptionCommand command);
    Object confirmReturn(ExceptionCommand command);

    record Actor(Long principalId, Long legacyUserId, String role, Object simulationContext) { }
    record AcceptDeliveryCommand(Long orderId, String action, String notes, String rejectReason,
                                 Double estimatedPickupTime, Double currentLat, Double currentLng, Actor actor) { }
    record CancelAssignmentCommand(Long orderId, String reason, Actor actor) { }
    record AcceptBatchCommand(UUID batchId, String notes, Double currentLat, Double currentLng, Actor actor) { }
    record RejectBatchCommand(UUID batchId, String reason, Actor actor) { }
    record UpdateStatusCommand(Long deliveryId, String status, Actor actor) { }
    record CreateProofUploadIntentCommand(Long deliveryId, String contentType, long contentLengthBytes, Actor actor) { }
    record ConfirmProofUploadCommand(Long deliveryId, UUID proofId, Actor actor) { }
    record ReportFailureCommand(Long deliveryId, String reason, Actor actor) { }
    record ExceptionCommand(Long deliveryId, Actor actor) { }
}
