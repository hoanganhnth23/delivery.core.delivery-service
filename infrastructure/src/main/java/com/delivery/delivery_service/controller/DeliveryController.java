package com.delivery.delivery_service.controller;

import com.delivery.delivery_service.common.constants.ApiPathConstants;
import com.delivery.delivery_service.dto.request.AcceptDeliveryRequest;
import com.delivery.delivery_service.dto.request.AcceptBatchRequest;
import com.delivery.delivery_service.dto.request.RejectBatchRequest;
import com.delivery.delivery_service.dto.request.CancelDeliveryAssignmentRequest;
import com.delivery.delivery_service.dto.request.CreateProofUploadIntentRequest;
import com.delivery.delivery_service.dto.request.ReportDeliveryFailureRequest;
import com.delivery.delivery_service.dto.response.DeliveryResponse;
import com.delivery.delivery_service.dto.response.DeliveryOfferResponse;
import com.delivery.delivery_service.dto.response.ProofAccessResponse;
import com.delivery.delivery_service.dto.response.ProofOfDeliveryResponse;
import com.delivery.delivery_service.dto.response.ProofUploadIntentResponse;
import com.delivery.delivery_service.dto.response.DeliveryExceptionResponse;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.payload.BaseResponse;
import com.delivery.delivery_service.service.DeliveryService;
import com.delivery.delivery_service.service.DeliveryBatchAcceptanceService;
import com.delivery.delivery_service.service.DeliveryBatchLifecycleService;
import com.delivery.delivery_service.service.DeliveryProofOfDeliveryService;
import com.delivery.delivery_service.service.DeliveryExceptionService;
import com.delivery.auth.resourceserver.security.AuthenticatedActor;
import com.delivery.identity.contracts.SimulationContext;
import com.delivery.delivery.application.api.DeliveryCommandPort;
import com.delivery.delivery.application.api.DeliveryQueryPort;
import com.delivery.delivery_service.adapter.DeliveryApplicationPorts;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPathConstants.DELIVERIES)
public class DeliveryController {

    private final DeliveryCommandPort commandPort;
    private final DeliveryQueryPort queryPort;

    @Autowired
    public DeliveryController(@Qualifier("deliveryCommandPort") DeliveryCommandPort commandPort,
            @Qualifier("deliveryQueryPort") DeliveryQueryPort queryPort) {
        this.commandPort = commandPort;
        this.queryPort = queryPort;
    }

    public DeliveryController(DeliveryService deliveryService, DeliveryBatchAcceptanceService batchAcceptanceService,
                              DeliveryBatchLifecycleService batchLifecycleService,
                              com.delivery.delivery_service.service.ShipperIdentityResolver shipperIdentityResolver,
                              com.delivery.delivery_service.service.DeliveryBatchSnapshotService batchSnapshotService,
                              DeliveryProofOfDeliveryService proofOfDeliveryService,
                              DeliveryExceptionService deliveryExceptionService) {
        this(new DeliveryApplicationPorts(deliveryService, batchAcceptanceService, batchLifecycleService,
                shipperIdentityResolver, batchSnapshotService, proofOfDeliveryService, deliveryExceptionService),
                new DeliveryApplicationPorts(deliveryService, batchAcceptanceService, batchLifecycleService,
                        shipperIdentityResolver, batchSnapshotService, proofOfDeliveryService, deliveryExceptionService));
    }

    /** Compatibility constructor for batch controller fixtures. */
    public DeliveryController(DeliveryService deliveryService, DeliveryBatchAcceptanceService batchAcceptanceService,
                              DeliveryBatchLifecycleService batchLifecycleService,
                              com.delivery.delivery_service.service.ShipperIdentityResolver shipperIdentityResolver,
                              com.delivery.delivery_service.service.DeliveryBatchSnapshotService batchSnapshotService) {
        this(new DeliveryApplicationPorts(deliveryService, batchAcceptanceService, batchLifecycleService,
                        shipperIdentityResolver, batchSnapshotService, null, null),
                new DeliveryApplicationPorts(deliveryService, batchAcceptanceService, batchLifecycleService,
                        shipperIdentityResolver, batchSnapshotService, null, null));
    }

    /** Compatibility constructor for existing controller fixtures. */
    public DeliveryController(DeliveryService deliveryService, DeliveryBatchAcceptanceService batchAcceptanceService,
                              DeliveryBatchLifecycleService batchLifecycleService) {
        this(new DeliveryApplicationPorts(deliveryService, batchAcceptanceService, batchLifecycleService,
                        null, null, null, null),
                        new DeliveryApplicationPorts(deliveryService, batchAcceptanceService, batchLifecycleService,
                        null, null, null, null));
    }

    /** Compatibility constructor for legacy controller authorization tests. */
    public DeliveryController(DeliveryService deliveryService) {
        DeliveryApplicationPorts ports = new DeliveryApplicationPorts(deliveryService, null, null, null, null, null, null);
        this.commandPort = ports;
        this.queryPort = ports;
    }

    @PostMapping("/batch/accept")
    public ResponseEntity<BaseResponse<DeliveryResponse>> acceptBatch(
            @Valid @RequestBody AcceptBatchRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryCommandPort.Actor a = actorOf(actor);
        DeliveryResponse response = (DeliveryResponse) commandPort.acceptBatch(new DeliveryCommandPort.AcceptBatchCommand(
                request.getBatchId(), request.getNotes(), request.getCurrentLat(), request.getCurrentLng(), a));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Nhận batch thành công"));
    }

    @PostMapping("/batch/reject")
    public ResponseEntity<BaseResponse<Void>> rejectBatch(
            @Valid @RequestBody RejectBatchRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        commandPort.rejectBatch(new DeliveryCommandPort.RejectBatchCommand(request.getBatchId(), request.getReason(), actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, null, "Đã từ chối batch"));
    }

    @PostMapping("/accept")
    public ResponseEntity<BaseResponse<DeliveryResponse>> acceptDelivery(
            @Valid @RequestBody AcceptDeliveryRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryResponse response = (DeliveryResponse) commandPort.acceptDelivery(new DeliveryCommandPort.AcceptDeliveryCommand(
                request.getOrderId(), request.getAction(), request.getNotes(), request.getRejectReason(),
                request.getEstimatedPickupTime(), request.getCurrentLat(), request.getCurrentLng(), actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Nhận đơn hàng thành công"));
    }

    @PostMapping("/cancel-assignment")
    public ResponseEntity<BaseResponse<DeliveryResponse>> cancelAssignedDelivery(
            @Valid @RequestBody CancelDeliveryAssignmentRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryResponse response = (DeliveryResponse) commandPort.cancelAssignment(new DeliveryCommandPort.CancelAssignmentCommand(
                request.getOrderId(), request.getReason(), actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Đã huỷ đơn, đang tìm shipper mới"));
    }

    @GetMapping("/offers/current")
    public ResponseEntity<BaseResponse<DeliveryOfferResponse>> getCurrentOffer(
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryOfferResponse response = (DeliveryOfferResponse) queryPort.currentOffer(actorOf(actor));
        return ResponseEntity.ok(new BaseResponse<>(1, response,
                response == null ? "Không có offer đang hoạt động" : "Lấy offer hiện tại thành công"));
    }

    @GetMapping("/offers/current-batch")
    public ResponseEntity<BaseResponse<com.delivery.delivery_service.dto.response.DeliveryBatchOfferResponse>> getCurrentBatchOffer(
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        var response = (com.delivery.delivery_service.dto.response.DeliveryBatchOfferResponse) queryPort.currentBatchOffer(actorOf(actor));
        return ResponseEntity.ok(new BaseResponse<>(1, response,
                response == null ? "Không có batch offer đang hoạt động" : "Lấy batch offer thành công"));
    }

    /** Protected durable batch recovery; single-order routes remain unchanged. */
    @GetMapping("/batches/{batchId}")
    public ResponseEntity<BaseResponse<com.delivery.delivery_service.dto.response.DeliveryBatchSnapshotResponse>> getBatchSnapshot(
            @PathVariable UUID batchId,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        var response = (com.delivery.delivery_service.dto.response.DeliveryBatchSnapshotResponse) queryPort.batchSnapshot(
                new DeliveryQueryPort.BatchSnapshotQuery(batchId, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Lấy snapshot batch thành công"));
    }

    @PostMapping("/{deliveryId}/proofs/upload-intent")
    public ResponseEntity<BaseResponse<ProofUploadIntentResponse>> createProofUploadIntent(
            @PathVariable Long deliveryId,
            @Valid @RequestBody CreateProofUploadIntentRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        ProofUploadIntentResponse response = (ProofUploadIntentResponse) commandPort.createProofUploadIntent(
                new DeliveryCommandPort.CreateProofUploadIntentCommand(deliveryId, request.getContentType(),
                        request.getContentLengthBytes(), actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Đã tạo URL tải bằng chứng riêng tư"));
    }

    @PostMapping("/{deliveryId}/proofs/{proofId}/confirm")
    public ResponseEntity<BaseResponse<ProofOfDeliveryResponse>> confirmProofUpload(
            @PathVariable Long deliveryId,
            @PathVariable UUID proofId,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        ProofOfDeliveryResponse response = (ProofOfDeliveryResponse) commandPort.confirmProofUpload(
                new DeliveryCommandPort.ConfirmProofUploadCommand(deliveryId, proofId, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Đã xác nhận bằng chứng giao hàng"));
    }

    @GetMapping("/{deliveryId}/proofs/{proofId}/access")
    public ResponseEntity<BaseResponse<ProofAccessResponse>> createProofReadAccess(
            @PathVariable Long deliveryId,
            @PathVariable UUID proofId,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        ProofAccessResponse response = (ProofAccessResponse) queryPort.proofAccess(
                new DeliveryQueryPort.ProofAccessQuery(deliveryId, proofId, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Đã tạo URL xem bằng chứng riêng tư"));
    }

    @PostMapping("/{deliveryId}/exceptions/failed")
    public ResponseEntity<BaseResponse<DeliveryExceptionResponse>> reportDeliveryFailure(
            @PathVariable Long deliveryId,
            @Valid @RequestBody ReportDeliveryFailureRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryExceptionResponse response = (DeliveryExceptionResponse) commandPort.reportFailure(
                new DeliveryCommandPort.ReportFailureCommand(deliveryId, request.getReason(), actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Đã ghi nhận sự cố giao hàng"));
    }

    @PostMapping("/{deliveryId}/exceptions/retry")
    public ResponseEntity<BaseResponse<DeliveryExceptionResponse>> useDeliveryRetry(
            @PathVariable Long deliveryId,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryExceptionResponse response = (DeliveryExceptionResponse) commandPort.useRetry(
                new DeliveryCommandPort.ExceptionCommand(deliveryId, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Đã dùng lượt giao lại"));
    }

    @PostMapping("/{deliveryId}/exceptions/return/confirm")
    public ResponseEntity<BaseResponse<DeliveryExceptionResponse>> confirmDeliveryReturn(
            @PathVariable Long deliveryId,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryExceptionResponse response = (DeliveryExceptionResponse) commandPort.confirmReturn(
                new DeliveryCommandPort.ExceptionCommand(deliveryId, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Nhà hàng đã xác nhận hoàn hàng"));
    }

    @GetMapping("/{deliveryId}/exception")
    public ResponseEntity<BaseResponse<DeliveryExceptionResponse>> getDeliveryException(
            @PathVariable Long deliveryId,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryExceptionResponse response = (DeliveryExceptionResponse) queryPort.exception(
                new DeliveryQueryPort.ExceptionQuery(deliveryId, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Lấy sự cố giao hàng thành công"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BaseResponse<DeliveryResponse>> getDelivery(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryResponse response = (DeliveryResponse) queryPort.deliveryById(new DeliveryQueryPort.DeliveryQuery(id, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Lấy thông tin delivery thành công"));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<BaseResponse<DeliveryResponse>> updateStatus(
            @PathVariable Long id,
            @RequestParam DeliveryStatus status,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryResponse response = (DeliveryResponse) commandPort.updateStatus(new DeliveryCommandPort.UpdateStatusCommand(
                id, status.name(), actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Cập nhật trạng thái delivery thành công"));
    }

    @GetMapping("/shipper/{shipperId}")
    public ResponseEntity<BaseResponse<List<DeliveryResponse>>> getDeliveriesByShipper(
            @PathVariable Long shipperId,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        @SuppressWarnings("unchecked") List<DeliveryResponse> response = (List<DeliveryResponse>) queryPort.deliveriesByShipper(
                new DeliveryQueryPort.ShipperDeliveriesQuery(shipperId, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Lấy danh sách delivery của shipper thành công"));
    }

    @GetMapping("/shipper/{shipperId}/active")
    public ResponseEntity<BaseResponse<List<DeliveryResponse>>> getActiveDeliveriesByShipper(
            @PathVariable Long shipperId,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        @SuppressWarnings("unchecked") List<DeliveryResponse> response = (List<DeliveryResponse>) queryPort.activeDeliveriesByShipper(
                new DeliveryQueryPort.ShipperDeliveriesQuery(shipperId, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Lấy danh sách delivery đang hoạt động thành công"));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<BaseResponse<DeliveryResponse>> getDeliveryByOrderId(
            @PathVariable Long orderId,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        requireActor(actor);
        DeliveryResponse response = (DeliveryResponse) queryPort.deliveryByOrder(new DeliveryQueryPort.DeliveryQuery(orderId, actorOf(actor)));
        return ResponseEntity.ok(new BaseResponse<>(1, response, "Lấy thông tin delivery theo order thành công"));
    }

    private void requireActor(AuthenticatedActor actor) {
        if (actor == null || actor.getPrincipalId() == null || actor.getLegacyUserId() == null) {
            throw new AccessDeniedException("Yêu cầu đăng nhập");
        }
    }

    private DeliveryCommandPort.Actor actorOf(AuthenticatedActor actor) {
        return new DeliveryCommandPort.Actor(actor.getPrincipalId(), actor.getLegacyUserId(),
                getRoleString(actor), actor.getSimulationContext());
    }

    private String getRoleString(AuthenticatedActor actor) {
        if (actor == null) return null;
        if (actor.isAdmin()) return "ADMIN";
        if (actor.isShipper()) return "SHIPPER";
        if (actor.isShopOwner()) return "SHOP_OWNER";
        return "USER";
    }
}
