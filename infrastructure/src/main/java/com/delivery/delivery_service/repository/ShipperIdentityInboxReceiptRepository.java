package com.delivery.delivery_service.repository;

import com.delivery.delivery_service.entity.ShipperIdentityInboxReceipt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipperIdentityInboxReceiptRepository extends JpaRepository<ShipperIdentityInboxReceipt, UUID> {}
