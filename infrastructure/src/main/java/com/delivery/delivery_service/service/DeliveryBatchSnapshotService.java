package com.delivery.delivery_service.service;

import com.delivery.delivery_service.common.constants.RoleConstants;
import com.delivery.delivery_service.dto.response.DeliveryBatchSnapshotItemResponse;
import com.delivery.delivery_service.dto.response.DeliveryBatchSnapshotResponse;
import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryBatch;
import com.delivery.delivery_service.entity.DeliveryBatchItem;
import com.delivery.delivery_service.exception.AccessDeniedException;
import com.delivery.delivery_service.exception.InvalidStatusException;
import com.delivery.delivery_service.mapper.DeliveryMapper;
import com.delivery.delivery_service.repository.DeliveryBatchItemRepository;
import com.delivery.delivery_service.repository.DeliveryBatchRepository;
import com.delivery.delivery_service.repository.DeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only projection boundary for durable batch recovery. */
@Service
public class DeliveryBatchSnapshotService {

    @Value("${delivery.batch.enabled:false}")
    private boolean batchEnabled;

    private final DeliveryBatchRepository batchRepository;
    private final DeliveryBatchItemRepository itemRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryMapper deliveryMapper;
    private final ShipperIdentityResolver shipperIdentityResolver;

    public DeliveryBatchSnapshotService(DeliveryBatchRepository batchRepository,
                                       DeliveryBatchItemRepository itemRepository,
                                       DeliveryRepository deliveryRepository,
                                       DeliveryMapper deliveryMapper,
                                       ShipperIdentityResolver shipperIdentityResolver) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryMapper = deliveryMapper;
        this.shipperIdentityResolver = shipperIdentityResolver;
    }

    @Transactional(readOnly = true)
    public DeliveryBatchSnapshotResponse getSnapshot(UUID batchId,
                                                     Long principalId,
                                                     Long legacyUserId,
                                                     String role) {
        if (!batchEnabled) {
            throw new InvalidStatusException("Delivery batch dispatch is disabled");
        }
        Long shipperId = shipperIdentityResolver.resolveShipperId(principalId, legacyUserId, role);
        if (batchId == null) throw new InvalidStatusException("Batch ID is required");

        DeliveryBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new InvalidStatusException("Không tìm thấy batch"));
        if (!RoleConstants.SHIPPER.equals(role) || !shipperId.equals(batch.getShipperId())) {
            throw new AccessDeniedException("Batch không thuộc shipper này");
        }
        if (batch.getRouteVersion() <= 0 || batch.getTotalCodAmount() == null
                || batch.getTotalCodAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidStatusException("Batch snapshot financial metadata is invalid");
        }

        List<DeliveryBatchItem> items = itemRepository.findByBatchIdOrderByPickupSequenceAsc(batchId);
        DeliveryBatchRouteValidator.validatePersisted(items);
        Set<Long> orderIds = new HashSet<>();
        List<DeliveryBatchSnapshotItemResponse> snapshotItems = items.stream().map(item -> {
            Delivery delivery = deliveryRepository.findById(item.getDeliveryId())
                    .orElseThrow(() -> new InvalidStatusException("Batch delivery không tồn tại"));
            if (!batchId.equals(delivery.getBatchId())
                    || (!shipperId.equals(delivery.getShipperId())
                        && !shipperId.equals(delivery.getOfferedShipperId()))
                    || !orderIds.add(delivery.getOrderId())) {
                throw new InvalidStatusException("Batch snapshot ownership or order invariant is invalid");
            }
            DeliveryBatchSnapshotItemResponse response = new DeliveryBatchSnapshotItemResponse();
            response.setDeliveryId(delivery.getId());
            response.setOrderId(delivery.getOrderId());
            response.setPickupSequence(item.getPickupSequence());
            response.setDropoffSequence(item.getDropoffSequence());
            response.setItemStatus(item.getItemStatus());
            response.setDelivery(deliveryMapper.deliveryToDeliveryResponse(delivery));
            return response;
        }).toList();

        DeliveryBatchSnapshotResponse response = new DeliveryBatchSnapshotResponse();
        response.setBatchId(batch.getBatchId());
        response.setStatus(batch.getStatus());
        response.setRouteVersion(batch.getRouteVersion());
        response.setExpiresAt(batch.getOfferExpiresAt());
        response.setTotalCodAmount(batch.getTotalCodAmount());
        response.setItems(snapshotItems);
        return response;
    }
}
