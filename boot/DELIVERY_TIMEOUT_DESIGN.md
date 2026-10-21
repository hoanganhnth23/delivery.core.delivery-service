# Delivery Timeout & Auto-Cancel Design

## Mục tiêu
Tự động xử lý khi shipper không giao hàng hoặc tắt app giữa chừng, đảm bảo đơn hàng không bị treo vĩnh viễn.

## Cơ chế: `@Scheduled` trong delivery-service

Chạy mỗi **1 phút**, query DB tìm delivery quá hạn, xử lý theo tier.

## Phát hiện shipper offline

| Nguồn | Cơ chế | Thời gian phát hiện |
|-------|--------|---------------------|
| Redis location key TTL | Shipper không gửi GPS → key hết hạn → `getCachedShipperLocation()` = null | ~60s |
| WebSocket ping/pong | Không nhận ping → timeout | ~2 phút |
| `@Scheduled` check | Poll tracking-service API kiểm tra lastPing | ~1 phút (theo interval) |

## Timeout Tiers

```
ASSIGNED (shipper đã nhận đơn, chưa lấy hàng):
  → 15 phút không pickup    → Push warning cho shipper
  → 30 phút không pickup    → Auto cancel + tìm shipper mới

PICKED_UP (đã lấy hàng, đang di chuyển):
  → 45 phút không deliver   → Push warning cho shipper + notify customer
  → 60 phút không deliver   → Escalate to admin (không auto cancel vì đã có hàng)

DELIVERING:
  → 60 phút                 → Escalate to admin
```

## Flow xử lý

```
@Scheduled(fixedRate = 60_000)
checkStaleDeliveries()
│
├─ Query: SELECT * FROM deliveries 
│  WHERE status IN ('ASSIGNED','PICKED_UP','DELIVERING')
│  AND updated_at < NOW() - INTERVAL '15 minutes'
│
├─ Với mỗi delivery quá hạn:
│  │
│  ├─ Gọi tracking-service: GET /api/shipper-locations/{id}
│  │   → Kiểm tra shipper có online không (lastPing)
│  │
│  ├─ Shipper OFFLINE > 10 phút + status = ASSIGNED:
│  │   → Cancel delivery
│  │   → markShipperAvailable() (Redis)
│  │   → Publish "find-shipper" event (tìm shipper mới)
│  │   → Notify customer qua WebSocket
│  │
│  ├─ Shipper ONLINE nhưng quá timeout tier:
│  │   → Push notification cảnh báo cho shipper
│  │   → Nếu vượt tier tiếp → cancel hoặc escalate
│  │
│  └─ Shipper quay lại app trước deadline:
│     → fetchActiveDelivery() → resume (đã implement)
│     → Không cancel, reset timer
```

## Files cần tạo/sửa

### [NEW] `DeliveryTimeoutScheduler.java`
- `@Component` + `@Scheduled(fixedRate = 60_000)`
- Inject `DeliveryRepository`, `TrackingServiceClient`, `DeliveryEventPublisher`
- Logic check stale + xử lý theo tier

### [MODIFY] `DeliveryRepository.java`
- Thêm query `findStaleDeliveries(LocalDateTime threshold)`
```sql
SELECT d FROM Delivery d 
WHERE d.status IN ('ASSIGNED','PICKED_UP','DELIVERING') 
AND d.updatedAt < :threshold
```

### [MODIFY] `TrackingServiceClient.java`
- Thêm method `getShipperLocation(Long shipperId)` 
- Gọi `GET /api/shipper-locations/{id}` để check online status

### [NEW] `DeliveryTimeoutEvent.java` (DTO)
- `deliveryId`, `orderId`, `shipperId`, `timeoutType`, `reason`

### [MODIFY] `DeliveryEventPublisher.java`
- Thêm `publishDeliveryTimeoutEvent()`
- Topic: `delivery-timeout`

### Config (`application.properties`)
```properties
delivery.timeout.assigned-warning-minutes=15
delivery.timeout.assigned-cancel-minutes=30
delivery.timeout.picked-up-warning-minutes=45
delivery.timeout.escalate-minutes=60
delivery.timeout.shipper-offline-minutes=10
```

## Kết hợp với shipper app restore

Khi shipper tắt app rồi mở lại:
1. `fetchActiveDelivery()` → restore đơn (đã implement ✅)
2. Nếu quay lại **trước deadline** → delivery tiếp tục bình thường
3. Nếu quay lại **sau deadline** → đơn đã bị cancel → app hiện thông báo

## Kết hợp với Saga Orchestrator (tương lai)

Khi saga orchestrator hoạt động, `DeliveryTimeoutScheduler` sẽ publish event `delivery-timeout` lên Kafka. Saga orchestrator sẽ listen event này và điều phối compensation flow (refund, notify, reassign) thay vì delivery-service tự xử lý.
