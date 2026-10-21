package com.delivery.delivery_service.controller;

import com.delivery.delivery_service.metrics.BusinessMetrics;
import com.delivery.delivery_service.repository.ShipperIdentityProjectionRepository;
import com.delivery.auth.resourceserver.security.AuthenticatedActor;
import com.delivery.identity.contracts.SimulationContext;
import com.delivery.delivery_service.dto.request.*;
import com.delivery.delivery_service.dto.response.*;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.payload.BaseResponse;
import com.delivery.delivery_service.service.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the HTTP boundary through the real application facade, preserving both identity IDs. */
class DeliveryControllerBehaviorTest {
    final DeliveryService delivery = mock(DeliveryService.class);
    final DeliveryBatchAcceptanceService batches = mock(DeliveryBatchAcceptanceService.class);
    final DeliveryBatchLifecycleService lifecycle = mock(DeliveryBatchLifecycleService.class);
    final ShipperIdentityResolver identities = ShipperIdentityResolver.compatibility(
            mock(ShipperIdentityProjectionRepository.class), mock(BusinessMetrics.class), false);
    final DeliveryBatchSnapshotService snapshots = mock(DeliveryBatchSnapshotService.class);
    final DeliveryProofOfDeliveryService proofs = mock(DeliveryProofOfDeliveryService.class);
    final DeliveryExceptionService exceptions = mock(DeliveryExceptionService.class);
    final DeliveryController controller = new DeliveryController(delivery, batches, lifecycle, identities, snapshots, proofs, exceptions);
    final AuthenticatedActor shipper = new AuthenticatedActor(10L, 100L, "shipper@example.com", Set.of("SHIPPER"));
    final UUID batchId = UUID.randomUUID();
    final UUID proofId = UUID.randomUUID();

    <T> void success(ResponseEntity<BaseResponse<T>> response, T data, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getStatus()).isEqualTo(1);
        assertThat(response.getBody().getData()).isSameAs(data);
        assertThat(response.getBody().getMessage()).isEqualTo(message);
    }

    @Test void batchCommandsPreserveRequestAndActor() {
        var accepted = new DeliveryResponse();
        when(batches.accept(any(), eq(10L), eq(100L), eq("SHIPPER"))).thenReturn(accepted);
        var request = new AcceptBatchRequest(); request.setBatchId(batchId); request.setNotes("ready");
        request.setCurrentLat(10.5); request.setCurrentLng(106.5);
        success(controller.acceptBatch(request, shipper), accepted, "Nhận batch thành công");
        verify(batches).accept(argThat(r -> batchId.equals(r.getBatchId()) && "ready".equals(r.getNotes())
                && r.getCurrentLat().equals(10.5) && r.getCurrentLng().equals(106.5)), eq(10L), eq(100L), eq("SHIPPER"));
        var reject = new RejectBatchRequest(); reject.setBatchId(batchId); reject.setReason("busy");
        success(controller.rejectBatch(reject, shipper), null, "Đã từ chối batch");
        verify(lifecycle).reject(batchId, 10L, 100L, "SHIPPER", "busy");
    }

    @Test void cancelAndStatusCommandsCarrySimulationContext() {
        var response = new DeliveryResponse();
        when(delivery.cancelAssignedDelivery(77L, 10L, 100L, "SHIPPER", "bike broken", shipper.getSimulationContext())).thenReturn(response);
        var request = new CancelDeliveryAssignmentRequest(); request.setOrderId(77L); request.setReason("bike broken");
        success(controller.cancelAssignedDelivery(request, shipper), response, "Đã huỷ đơn, đang tìm shipper mới");
        when(delivery.updateDeliveryStatus(1L, DeliveryStatus.PICKED_UP, 10L, 100L, "SHIPPER", shipper.getSimulationContext())).thenReturn(response);
        success(controller.updateStatus(1L, DeliveryStatus.PICKED_UP, shipper), response, "Cập nhật trạng thái delivery thành công");
        verify(delivery).cancelAssignedDelivery(77L, 10L, 100L, "SHIPPER", "bike broken", shipper.getSimulationContext());
        verify(delivery).updateDeliveryStatus(1L, DeliveryStatus.PICKED_UP, 10L, 100L, "SHIPPER", shipper.getSimulationContext());
    }

    @Test void offerAndBatchRecoveryReturnDurableDataAndEmptyMessage() {
        var offer = new DeliveryOfferResponse(); var batch = new DeliveryBatchOfferResponse();
        var snapshot = new DeliveryBatchSnapshotResponse();
        when(delivery.getCurrentOffer(10L,100L,"SHIPPER")).thenReturn(offer);
        when(batches.currentOffer(10L,100L,"SHIPPER")).thenReturn(batch, (DeliveryBatchOfferResponse)null);
        when(snapshots.getSnapshot(batchId,10L,100L,"SHIPPER")).thenReturn(snapshot);
        success(controller.getCurrentOffer(shipper), offer, "Lấy offer hiện tại thành công");
        success(controller.getCurrentBatchOffer(shipper), batch, "Lấy batch offer thành công");
        success(controller.getCurrentBatchOffer(shipper), null, "Không có batch offer đang hoạt động");
        success(controller.getBatchSnapshot(batchId,shipper), snapshot, "Lấy snapshot batch thành công");
        verify(snapshots).getSnapshot(batchId,10L,100L,"SHIPPER");
    }

    @Test void proofCommandsAndAccessPreservePrivateObjectIdentityAndMetadata() {
        var intent = new ProofUploadIntentResponse(); var confirmed = new ProofOfDeliveryResponse(); var access = new ProofAccessResponse();
        when(proofs.createUploadIntent(eq(1L),any(),eq(10L),eq(100L),eq("SHIPPER"))).thenReturn(intent);
        when(proofs.confirmUpload(1L,proofId,10L,100L,"SHIPPER")).thenReturn(confirmed);
        when(proofs.createReadAccess(1L,proofId,10L,100L,"SHIPPER")).thenReturn(access);
        var request = new CreateProofUploadIntentRequest(); request.setContentType("image/png"); request.setContentLengthBytes(1234);
        success(controller.createProofUploadIntent(1L,request,shipper), intent, "Đã tạo URL tải bằng chứng riêng tư");
        success(controller.confirmProofUpload(1L,proofId,shipper), confirmed, "Đã xác nhận bằng chứng giao hàng");
        success(controller.createProofReadAccess(1L,proofId,shipper), access, "Đã tạo URL xem bằng chứng riêng tư");
        verify(proofs).createUploadIntent(eq(1L),argThat(r -> "image/png".equals(r.getContentType()) && r.getContentLengthBytes()==1234),eq(10L),eq(100L),eq("SHIPPER"));
        verify(proofs).confirmUpload(1L,proofId,10L,100L,"SHIPPER");
        verify(proofs).createReadAccess(1L,proofId,10L,100L,"SHIPPER");
    }

    @Test void exceptionCommandsPreserveActorRoleAndReason() {
        var response = new DeliveryExceptionResponse();
        var owner = new AuthenticatedActor(20L,200L,"owner@example.com",Set.of("SHOP_OWNER"));
        when(exceptions.reportFailure(1L,"customer absent",10L,100L,"SHIPPER")).thenReturn(response);
        when(exceptions.useRetry(1L,10L,100L,"SHIPPER")).thenReturn(response);
        when(exceptions.confirmReturn(1L,20L,200L,"SHOP_OWNER")).thenReturn(response);
        when(exceptions.getException(1L,10L,100L,"SHIPPER")).thenReturn(response);
        var request = new ReportDeliveryFailureRequest(); request.setReason("customer absent");
        success(controller.reportDeliveryFailure(1L,request,shipper),response,"Đã ghi nhận sự cố giao hàng");
        success(controller.useDeliveryRetry(1L,shipper),response,"Đã dùng lượt giao lại");
        success(controller.confirmDeliveryReturn(1L,owner),response,"Nhà hàng đã xác nhận hoàn hàng");
        success(controller.getDeliveryException(1L,shipper),response,"Lấy sự cố giao hàng thành công");
        verify(exceptions).reportFailure(1L,"customer absent",10L,100L,"SHIPPER");
        verify(exceptions).useRetry(1L,10L,100L,"SHIPPER");
        verify(exceptions).confirmReturn(1L,20L,200L,"SHOP_OWNER");
        verify(exceptions).getException(1L,10L,100L,"SHIPPER");
    }

    @Test void readEndpointsReturnServiceDataWithCanonicalIdentity() {
        var response = new DeliveryResponse(); var list = List.of(response);
        var admin = new AuthenticatedActor(30L,300L,"admin@example.com",Set.of("ADMIN"));
        var user = new AuthenticatedActor(40L,400L,"user@example.com",Set.of("USER"));
        when(delivery.getDeliveryById(1L,30L,300L,"ADMIN")).thenReturn(response);
        when(delivery.getDeliveryByOrderId(77L,40L,400L,"USER")).thenReturn(response);
        when(delivery.getDeliveriesByShipper(5L,30L,300L,"ADMIN")).thenReturn(list);
        when(delivery.getActiveDeliveriesByShipper(5L,30L,300L,"ADMIN")).thenReturn(list);
        success(controller.getDelivery(1L,admin),response,"Lấy thông tin delivery thành công");
        success(controller.getDeliveryByOrderId(77L,user),response,"Lấy thông tin delivery theo order thành công");
        success(controller.getDeliveriesByShipper(5L,admin),list,"Lấy danh sách delivery của shipper thành công");
        success(controller.getActiveDeliveriesByShipper(5L,admin),list,"Lấy danh sách delivery đang hoạt động thành công");
        verify(delivery).getDeliveryById(1L,30L,300L,"ADMIN");
        verify(delivery).getDeliveryByOrderId(77L,40L,400L,"USER");
        verify(delivery).getDeliveriesByShipper(5L,30L,300L,"ADMIN");
        verify(delivery).getActiveDeliveriesByShipper(5L,30L,300L,"ADMIN");
    }
}
