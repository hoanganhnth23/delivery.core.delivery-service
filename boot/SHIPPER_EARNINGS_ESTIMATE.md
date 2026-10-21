# 💰 Shipper Earnings Estimate - Hiển thị Thu Nhập Ước Tính

## 📌 Tổng quan
Tài liệu này mô tả cách hệ thống hiển thị **thu nhập ước tính** cho shipper trước khi nhận đơn hàng, giúp shipper có thể quyết định có nhận đơn hay không dựa trên mức thu nhập.

## 🎯 Mục đích
- Hiển thị cho shipper biết họ sẽ kiếm được bao nhiêu từ một đơn hàng
- Tạo tính minh bạch trong việc chia sẻ doanh thu giữa platform và shipper
- Giúp shipper đưa ra quyết định có nên nhận đơn hay không

## 💡 Cơ chế hoạt động

### 1. Công thức tính thu nhập
```java
// Customer trả 100% shipping fee
shippingFee = baseFee + (distance × perKmRate) × surgeMultiplier

// Platform lấy 15% commission
platformCommission = shippingFee × 0.15

// Shipper nhận 85% còn lại
shipperEarnings = shippingFee × 0.85

// Đảm bảo shipper nhận tối thiểu 10,000 VNĐ
shipperEarnings = Math.max(shipperEarnings, 10,000)
```

### 2. Ví dụ tính toán thực tế

#### Đơn gần (2km)
```
Distance: 2km
Surge Multiplier: 1.0

shippingFee = 10,000 + (2 × 3,000) × 1.0 = 16,000 VNĐ
platformCommission = 16,000 × 0.15 = 2,400 VNĐ
shipperEarnings = 16,000 × 0.85 = 13,600 VNĐ
```

#### Đơn trung bình (5km, giờ cao điểm)
```
Distance: 5km
Surge Multiplier: 1.5

shippingFee = 10,000 + (3×3,000 + 2×4,000) × 1.5
            = 10,000 + 17,000 × 1.5
            = 10,000 + 25,500
            = 35,500 VNĐ

platformCommission = 35,500 × 0.15 = 5,325 VNĐ
shipperEarnings = 35,500 × 0.85 = 30,175 VNĐ
```

#### Đơn xa (12km)
```
Distance: 12km
Surge Multiplier: 1.0

shippingFee = 10,000 + (3×3,000 + 2×4,000 + 5×5,000 + 2×6,000) × 1.0
            = 10,000 + 60,000
            = 70,000 VNĐ

platformCommission = 70,000 × 0.15 = 10,500 VNĐ
shipperEarnings = 70,000 × 0.85 = 59,500 VNĐ
```

## 🔧 Triển khai kỹ thuật

### 1. DeliveryResponse DTO
```java
@Getter
@Setter
public class DeliveryResponse {
    // Basic fields...
    
    // ✅ Pricing Information
    private BigDecimal shippingFee;           // Tổng phí customer trả
    private BigDecimal estimatedEarnings;     // Thu nhập shipper (85%)
    private BigDecimal platformCommission;    // Hoa hồng platform (15%)
}
```

### 2. DeliveryMapper với Auto-calculation
```java
@Mapper(componentModel = "spring")
public interface DeliveryMapper {

    @Mapping(target = "estimatedEarnings", source = "shippingFee", qualifiedByName = "calculateShipperEarnings")
    @Mapping(target = "platformCommission", source = "shippingFee", qualifiedByName = "calculatePlatformCommission")
    DeliveryResponse deliveryToDeliveryResponse(Delivery delivery);

    @Named("calculateShipperEarnings")
    default BigDecimal calculateShipperEarnings(BigDecimal shippingFee) {
        return PricingConstants.calculateShipperEarnings(shippingFee);
    }

    @Named("calculatePlatformCommission")
    default BigDecimal calculatePlatformCommission(BigDecimal shippingFee) {
        return PricingConstants.calculatePlatformCommission(shippingFee);
    }
}
```

### 3. PricingConstants
```java
public class PricingConstants {
    
    public static final BigDecimal PLATFORM_COMMISSION_RATE = new BigDecimal("0.15");  // 15%
    public static final BigDecimal SHIPPER_EARNINGS_RATE = new BigDecimal("0.85");     // 85%
    public static final BigDecimal MIN_SHIPPER_EARNINGS = new BigDecimal("10000");     // 10,000 VNĐ

    public static BigDecimal calculateShipperEarnings(BigDecimal shippingFee) {
        if (shippingFee == null || shippingFee.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal earnings = shippingFee.multiply(SHIPPER_EARNINGS_RATE)
                .setScale(0, RoundingMode.HALF_UP);
        
        return earnings.max(MIN_SHIPPER_EARNINGS);
    }

    public static BigDecimal calculatePlatformCommission(BigDecimal shippingFee) {
        if (shippingFee == null || shippingFee.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        return shippingFee.multiply(PLATFORM_COMMISSION_RATE)
                .setScale(0, RoundingMode.HALF_UP);
    }
}
```

## 🧪 Testing Flow

### 1. Tạo đơn hàng mới
```bash
POST http://localhost:8084/api/orders
Content-Type: application/json
Authorization: Bearer <user-access-token>

{
  "restaurantId": 1,
  "items": [
    {
      "menuItemId": 1,
      "quantity": 2,
      "price": 50000,
      "notes": "Không hành"
    }
  ],
  "deliveryAddress": "123 Nguyễn Huệ, Q1, HCM",
  "deliveryLat": 10.7765,
  "deliveryLng": 106.7009,
  "notes": "Gọi trước 5 phút"
}
```

**Response sẽ có:**
```json
{
  "code": 1,
  "data": {
    "id": 1,
    "totalPrice": 85500,        // subtotal (100k) + shippingFee (35,500)
    "shippingFee": 35500        // Distance-based calculation
  }
}
```

### 2. Shipper xem danh sách đơn có thể nhận
```bash
GET http://localhost:8085/api/deliveries/available
Authorization: Bearer <shipper-access-token>
```

**Response:**
```json
{
  "code": 1,
  "data": [
    {
      "id": 1,
      "orderId": 1,
      "status": "PENDING",
      "pickupAddress": "Nhà hàng ABC, Q1",
      "deliveryAddress": "123 Nguyễn Huệ, Q1",
      "shippingFee": 35500,           // ✅ Customer trả
      "estimatedEarnings": 30175,     // ✅ Shipper nhận (85%)
      "platformCommission": 5325,     // ✅ Platform lấy (15%)
      "pickupLat": 10.7650,
      "pickupLng": 106.6920,
      "deliveryLat": 10.7765,
      "deliveryLng": 106.7009
    }
  ]
}
```

### 3. Shipper accept đơn hàng
```bash
POST http://localhost:8085/api/deliveries/{deliveryId}/accept
Authorization: Bearer <shipper-access-token>
```

**Response:**
```json
{
  "code": 1,
  "data": {
    "id": 1,
    "status": "ACCEPTED",
    "shippingFee": 35500,
    "estimatedEarnings": 30175,     // ✅ Shipper biết họ sẽ nhận 30,175 VNĐ
    "platformCommission": 5325
  }
}
```

### 4. Hoàn thành giao hàng
```bash
PUT http://localhost:8085/api/deliveries/{deliveryId}/status
Authorization: Bearer <shipper-access-token>

{
  "status": "DELIVERED",
  "shipperCurrentLat": 10.7765,
  "shipperCurrentLng": 106.7009
}
```

**Kafka Event được publish:**
```json
{
  "deliveryId": 1,
  "orderId": 1,
  "shipperId": 5,
  "shippingFee": 35500,           // Customer đã trả
  "shipperEarnings": 30175,       // ✅ Shipper nhận vào balance
  "platformCommission": 5325,     // Platform giữ lại
  "completedAt": "2024-01-15T10:30:00"
}
```

### 5. Kiểm tra balance của shipper
```bash
GET http://localhost:8086/api/shippers/balance
Authorization: Bearer <shipper-access-token>
```

**Response:**
```json
{
  "code": 1,
  "data": {
    "currentBalance": 30175,        // ✅ Đã được tự động cộng từ Kafka event
    "totalEarnings": 30175,
    "totalOrders": 1
  }
}
```

## 📊 API Endpoints có Earnings Estimate

### 1. GET /api/deliveries/available
**Mô tả:** Shipper xem danh sách đơn hàng khả dụng (chưa có shipper)

**Response fields:**
- `shippingFee`: Tổng phí customer trả
- `estimatedEarnings`: Thu nhập shipper (85%)
- `platformCommission`: Hoa hồng platform (15%)

### 2. GET /api/deliveries/{id}
**Mô tả:** Xem chi tiết một delivery

**Response fields:**
- `shippingFee`: Tổng phí customer trả
- `estimatedEarnings`: Thu nhập shipper (85%)
- `platformCommission`: Hoa hồng platform (15%)

### 3. POST /api/deliveries/{id}/accept
**Mô tả:** Shipper accept đơn hàng

**Response fields:**
- `shippingFee`: Tổng phí customer trả
- `estimatedEarnings`: Thu nhập shipper sẽ nhận (85%)
- `platformCommission`: Hoa hồng platform (15%)

### 4. GET /api/deliveries/shipper/{shipperId}
**Mô tả:** Xem lịch sử deliveries của shipper

**Response fields:**
- Mỗi delivery có `shippingFee`, `estimatedEarnings`, `platformCommission`

## 🎨 UI Display Recommendations

### Khi shipper xem danh sách đơn có thể nhận:
```
┌─────────────────────────────────────────────┐
│ Đơn hàng #1234                              │
│                                             │
│ 📍 Nhà hàng ABC → 123 Nguyễn Huệ           │
│ 📏 Khoảng cách: 5km                        │
│                                             │
│ 💰 Thu nhập: 30,175 VNĐ                    │
│    (từ phí giao: 35,500 VNĐ)              │
│                                             │
│ [NHẬN ĐơN]                                  │
└─────────────────────────────────────────────┘
```

### Khi shipper accept đơn:
```
✅ Đã nhận đơn #1234

💰 Thu nhập ước tính: 30,175 VNĐ
   - Phí giao hàng: 35,500 VNĐ
   - Bạn nhận: 30,175 VNĐ (85%)
   - Platform: 5,325 VNĐ (15%)

Hãy đến nhà hàng ABC để lấy món!
```

### Sau khi hoàn thành giao hàng:
```
🎉 Giao hàng thành công!

💰 Thu nhập đã cộng vào ví: 30,175 VNĐ
   - Phí giao hàng: 35,500 VNĐ
   - Bạn nhận: 30,175 VNĐ (85%)
   - Platform: 5,325 VNĐ (15%)

Tổng thu nhập hôm nay: 145,250 VNĐ
```

## ⚠️ Lưu ý quan trọng

### 1. Sự khác biệt giữa các field
- `shippingFee`: Số tiền **customer trả** (100%)
- `estimatedEarnings`: Số tiền **shipper nhận** (85%)
- `platformCommission`: Số tiền **platform giữ** (15%)

### 2. Validation rules
- `shippingFee` phải > 0
- `estimatedEarnings` >= 10,000 VNĐ (MIN_SHIPPER_EARNINGS)
- `estimatedEarnings + platformCommission = shippingFee`

### 3. Rounding
- Tất cả tính toán dùng `RoundingMode.HALF_UP`
- Scale = 0 (không có lẻ xu, chỉ có VNĐ)

### 4. Kafka Event Flow
```
OrderCreatedEvent 
  → Delivery created with shippingFee
  → Shipper accepts (sees estimatedEarnings)
  → Delivery completed
  → DeliveryCompletedEvent published
  → ShipperBalanceService credits shipperEarnings (85%)
```

## 📈 Business Insights

### Tỷ lệ chia sẻ doanh thu
- **Shipper: 85%** - Động lực làm việc
- **Platform: 15%** - Duy trì hệ thống, marketing, support

### So sánh với thị trường
- Grab: Platform lấy ~20-25%
- Shopee Food: Platform lấy ~15-20%
- Gojek: Platform lấy ~20%
- **DeliveryVN: 15%** ✅ Cạnh tranh tốt nhất

### Lợi ích của việc hiển thị estimate
1. **Tăng tỷ lệ accept**: Shipper biết rõ thu nhập trước khi nhận
2. **Giảm tranh chấp**: Minh bạch 100% về phí
3. **Tối ưu hiệu suất**: Shipper chọn đơn phù hợp với vị trí của mình

## 🔄 Migration & Deployment

### Database Migration
```sql
-- V3__add_shipping_fee_to_deliveries.sql
ALTER TABLE deliveries
ADD COLUMN shipping_fee DECIMAL(12, 2) DEFAULT 0;
```

### Dependencies Required
```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>1.5.5.Final</version>
</dependency>
```

### Restart Services
```bash
# 1. Run migration
# 2. Rebuild services
cd delivery-service && mvn clean install
cd shipper-service && mvn clean install

# 3. Restart
cd delivery-service && mvn spring-boot:run
cd shipper-service && mvn spring-boot:run
```

## ✅ Kết luận

Tính năng **Shipper Earnings Estimate** đảm bảo:
- ✅ Shipper biết chính xác họ sẽ kiếm được bao nhiêu
- ✅ Tính minh bạch trong chia sẻ doanh thu
- ✅ Tự động tính toán qua MapStruct
- ✅ Tự động cộng tiền qua Kafka event
- ✅ Tỷ lệ commission cạnh tranh (15%)

---

**Last Updated:** 2024-01-15  
**Author:** Development Team  
**Status:** ✅ Production Ready
