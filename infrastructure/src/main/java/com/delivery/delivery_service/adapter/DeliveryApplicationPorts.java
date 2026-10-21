package com.delivery.delivery_service.adapter;

import com.delivery.delivery.application.api.DeliveryCommandPort;
import com.delivery.delivery.application.api.DeliveryQueryPort;
import com.delivery.delivery_service.dto.request.AcceptBatchRequest;
import com.delivery.delivery_service.dto.request.AcceptDeliveryRequest;
import com.delivery.delivery_service.dto.request.CancelDeliveryAssignmentRequest;
import com.delivery.delivery_service.dto.request.CreateProofUploadIntentRequest;
import com.delivery.delivery_service.dto.request.ReportDeliveryFailureRequest;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.service.DeliveryBatchAcceptanceService;
import com.delivery.delivery_service.service.DeliveryBatchLifecycleService;
import com.delivery.delivery_service.service.DeliveryBatchSnapshotService;
import com.delivery.delivery_service.service.DeliveryExceptionService;
import com.delivery.delivery_service.service.DeliveryProofOfDeliveryService;
import com.delivery.delivery_service.service.DeliveryService;
import com.delivery.delivery_service.service.ShipperIdentityResolver;
import com.delivery.identity.contracts.SimulationContext;

/** Application-facing facade that composes the delivery service use cases behind stable ports. */
public final class DeliveryApplicationPorts implements DeliveryCommandPort, DeliveryQueryPort {
    private final DeliveryService delivery;
    private final DeliveryBatchAcceptanceService batches;
    private final DeliveryBatchLifecycleService lifecycle;
    private final ShipperIdentityResolver identities;
    private final DeliveryBatchSnapshotService snapshots;
    private final DeliveryProofOfDeliveryService proofs;
    private final DeliveryExceptionService exceptions;

    public DeliveryApplicationPorts(DeliveryService delivery, DeliveryBatchAcceptanceService batches,
            DeliveryBatchLifecycleService lifecycle, ShipperIdentityResolver identities,
            DeliveryBatchSnapshotService snapshots, DeliveryProofOfDeliveryService proofs,
            DeliveryExceptionService exceptions) {
        this.delivery = delivery; this.batches = batches; this.lifecycle = lifecycle; this.identities = identities;
        this.snapshots = snapshots; this.proofs = proofs; this.exceptions = exceptions;
    }

    @Override public Object acceptDelivery(AcceptDeliveryCommand c) {
        require(delivery, "delivery");
        AcceptDeliveryRequest request = new AcceptDeliveryRequest();
        request.setOrderId(c.orderId()); request.setAction(c.action()); request.setNotes(c.notes());
        request.setRejectReason(c.rejectReason()); request.setEstimatedPickupTime(c.estimatedPickupTime());
        request.setCurrentLat(c.currentLat()); request.setCurrentLng(c.currentLng());
        return delivery.acceptDelivery(request, principal(c.actor()), legacy(c.actor()), role(c.actor()), simulation(c.actor()));
    }

    @Override public Object cancelAssignment(CancelAssignmentCommand c) {
        require(delivery, "delivery");
        return delivery.cancelAssignedDelivery(c.orderId(), principal(c.actor()), legacy(c.actor()), role(c.actor()), c.reason(), simulation(c.actor()));
    }

    @Override public Object acceptBatch(AcceptBatchCommand c) {
        require(batches, "batch acceptance");
        AcceptBatchRequest request = new AcceptBatchRequest(); request.setBatchId(c.batchId());
        request.setNotes(c.notes()); request.setCurrentLat(c.currentLat()); request.setCurrentLng(c.currentLng());
        return batches.accept(request, principal(c.actor()), legacy(c.actor()), role(c.actor()));
    }

    @Override public void rejectBatch(RejectBatchCommand c) {
        require(lifecycle, "batch lifecycle");
        lifecycle.reject(c.batchId(), principal(c.actor()), legacy(c.actor()), role(c.actor()), c.reason());
    }

    @Override public Object updateStatus(UpdateStatusCommand c) {
        require(delivery, "delivery");
        return delivery.updateDeliveryStatus(c.deliveryId(), com.delivery.delivery_service.entity.DeliveryStatus.valueOf(c.status()),
                principal(c.actor()), legacy(c.actor()), role(c.actor()), simulation(c.actor()));
    }

    @Override public Object createProofUploadIntent(CreateProofUploadIntentCommand c) {
        require(proofs, "proof");
        CreateProofUploadIntentRequest request = new CreateProofUploadIntentRequest();
        request.setContentType(c.contentType()); request.setContentLengthBytes(c.contentLengthBytes());
        return proofs.createUploadIntent(c.deliveryId(), request, principal(c.actor()), legacy(c.actor()), role(c.actor()));
    }

    @Override public Object confirmProofUpload(ConfirmProofUploadCommand c) {
        require(proofs, "proof"); return proofs.confirmUpload(c.deliveryId(), c.proofId(), principal(c.actor()), legacy(c.actor()), role(c.actor()));
    }

    @Override public Object reportFailure(ReportFailureCommand c) {
        require(exceptions, "delivery exception");
        return exceptions.reportFailure(c.deliveryId(), c.reason(), principal(c.actor()), legacy(c.actor()), role(c.actor()));
    }

    @Override public Object useRetry(ExceptionCommand c) {
        require(exceptions, "delivery exception"); return exceptions.useRetry(c.deliveryId(), principal(c.actor()), legacy(c.actor()), role(c.actor()));
    }

    @Override public Object confirmReturn(ExceptionCommand c) {
        require(exceptions, "delivery exception"); return exceptions.confirmReturn(c.deliveryId(), principal(c.actor()), legacy(c.actor()), role(c.actor()));
    }

    @Override public Object currentOffer(DeliveryCommandPort.Actor a) {
        require(delivery, "delivery");
        return identities == null ? delivery.getCurrentOffer(a.principalId(), a.role())
                : delivery.getCurrentOffer(a.principalId(), a.legacyUserId(), a.role());
    }

    @Override public Object currentBatchOffer(DeliveryCommandPort.Actor a) {
        require(batches, "batch acceptance");
        return identities == null ? batches.currentOffer(a.principalId(), a.role())
                : batches.currentOffer(a.principalId(), a.legacyUserId(), a.role());
    }

    @Override public Object batchSnapshot(BatchSnapshotQuery q) {
        require(snapshots, "batch snapshot");
        var a = q.actor(); return snapshots.getSnapshot(q.batchId(), a.principalId(), a.legacyUserId(), a.role());
    }

    @Override public Object proofAccess(ProofAccessQuery q) {
        require(proofs, "proof"); var a = q.actor();
        return proofs.createReadAccess(q.deliveryId(), q.proofId(), a.principalId(), a.legacyUserId(), a.role());
    }

    @Override public Object exception(ExceptionQuery q) {
        require(exceptions, "delivery exception"); var a = q.actor();
        return exceptions.getException(q.deliveryId(), a.principalId(), a.legacyUserId(), a.role());
    }

    @Override public Object deliveryById(DeliveryQuery q) {
        require(delivery, "delivery"); var a = q.actor();
        return delivery.getDeliveryById(q.id(), a.principalId(), a.legacyUserId(), a.role());
    }

    @Override public Object deliveriesByShipper(ShipperDeliveriesQuery q) {
        require(delivery, "delivery"); var a = q.actor();
        return delivery.getDeliveriesByShipper(q.shipperId(), a.principalId(), a.legacyUserId(), a.role());
    }

    @Override public Object activeDeliveriesByShipper(ShipperDeliveriesQuery q) {
        require(delivery, "delivery"); var a = q.actor();
        return delivery.getActiveDeliveriesByShipper(q.shipperId(), a.principalId(), a.legacyUserId(), a.role());
    }

    @Override public Object deliveryByOrder(DeliveryQuery q) {
        require(delivery, "delivery"); var a = q.actor();
        return delivery.getDeliveryByOrderId(q.id(), a.principalId(), a.legacyUserId(), a.role());
    }

    private static Long principal(DeliveryCommandPort.Actor c) { return c.principalId(); }
    private static Long legacy(DeliveryCommandPort.Actor c) { return c.legacyUserId(); }
    private static String role(DeliveryCommandPort.Actor c) { return c.role(); }
    private static SimulationContext simulation(DeliveryCommandPort.Actor c) { return (SimulationContext) c.simulationContext(); }
    private static void require(Object value, String name) { if (value == null) throw new IllegalStateException(name + " support is unavailable"); }
}
