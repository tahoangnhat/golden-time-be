package com.goldentime.api.commerce.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.goldentime.api.common.ApiException;
import com.goldentime.api.commerce.payment.WalletService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class GhnShippingService {
    private final JdbcTemplate jdbc;
    private final WalletService wallet;
    private final RestClient client;
    private final String token;
    private final int shopId;
    private final String webhookSecret;
    private final int lengthCm;
    private final int widthCm;
    private final int heightCm;
    private final int packagingWeightGrams;
    private final ConcurrentMap<Integer, List<GhnAddress>> wardCache = new ConcurrentHashMap<>();
    private volatile List<GhnAddress> provinceCache;

    public GhnShippingService(JdbcTemplate jdbc, WalletService wallet,
            @Value("${app.shipping.ghn.api-base-url}") String apiBaseUrl,
            @Value("${app.shipping.ghn.token:}") String token,
            @Value("${app.shipping.ghn.shop-id:0}") int shopId,
            @Value("${app.shipping.ghn.webhook-secret:}") String webhookSecret,
            @Value("${app.shipping.ghn.default-length-cm:30}") int lengthCm,
            @Value("${app.shipping.ghn.default-width-cm:20}") int widthCm,
            @Value("${app.shipping.ghn.default-height-cm:15}") int heightCm,
            @Value("${app.shipping.ghn.packaging-weight-grams:100}") int packagingWeightGrams) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(15));
        this.client = RestClient.builder().baseUrl(apiBaseUrl.replaceAll("/+$", ""))
                .requestFactory(requestFactory).build();
        this.token = token == null ? "" : token.trim();
        this.shopId = shopId;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.packagingWeightGrams = packagingWeightGrams;
        if (lengthCm < 1 || lengthCm > 200 || widthCm < 1 || widthCm > 200
                || heightCm < 1 || heightCm > 200 || packagingWeightGrams < 0) {
            throw new IllegalArgumentException("GHN package dimensions must be 1-200 cm and packaging weight cannot be negative.");
        }
    }

    public boolean configured() {
        return !token.isBlank() && shopId > 0;
    }

    public List<GhnAddress> provinces() {
        requireConfigured();
        List<GhnAddress> result = provinceCache;
        if (result == null) {
            synchronized (this) {
                result = provinceCache;
                if (result == null) provinceCache = result = addressList(get("/v3/master-data/province/all"));
            }
        }
        return result;
    }

    public List<GhnAddress> wards(int provinceId) {
        requireConfigured();
        if (provinceId <= 0 || provinces().stream().noneMatch(item -> item.id() == provinceId)) {
            throw ApiException.badRequest("Tỉnh/thành GHN không hợp lệ.");
        }
        return wardCache.computeIfAbsent(provinceId, id -> addressList(get("/v3/master-data/ward/all-by-province-id", id)));
    }

    public Quote quote(long shopId, String recipientName, String recipientPhone, String deliveryAddress,
                       String provinceName, String wardName,
                       List<ShippingItem> items, long subtotal, long codAmount) {
        requireConfigured();
        String province = canonicalProvince(provinceName);
        String ward = canonicalWard(province, wardName);
        ShippingParty pickup = pickupParty(shopId);
        ShipmentSize size = size(items);
        long currentCod = codAmount;
        long fee = -1;
        Instant expected = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> request = shipmentRequest(0, recipientName, recipientPhone, deliveryAddress,
                    province, ward, pickup, size, subtotal, currentCod, false);
            JsonNode data = call("/v2/shipping-order/preview", request).path("data");
            if (data.isMissingNode() || data.isNull()) throw ApiException.badRequest("GHN không tính được phí cho địa chỉ này.");
            fee = data.path("total_fee").asLong(-1);
            if (fee < 0) throw ApiException.badRequest("GHN không trả về phí giao hàng hợp lệ.");
            expected = parseInstant(data.path("expected_delivery_time").asText(null));
            if (codAmount <= 0) break;
            long finalCod = Math.addExact(subtotal, fee);
            if (finalCod == currentCod) break;
            currentCod = finalCod;
        }
        return new Quote(fee, expected);
    }

    public ShipmentCreation createShipment(long orderId, long shopId) {
        requireConfigured();
        List<ShippingOrder> rows = jdbc.query("""
                SELECT o.id, o.total_amount, o.subtotal_amount, o.shipping_fee, o.payment_method,
                       o.delivery_name, o.delivery_phone, o.delivery_address,
                       o.delivery_province_name, o.delivery_ward_name,
                       s.name AS shop_name, s.address AS pickup_address,
                       s.pickup_province_name, s.pickup_ward_name,
                       COALESCE(NULLIF(s.business_phone, ''), NULLIF(s.representative_phone, ''), u.phone) AS pickup_phone
                FROM orders o
                JOIN shops s ON s.id = o.shop_id
                LEFT JOIN users u ON u.id = s.owner_user_id
                WHERE o.id = ? AND o.shop_id = ?
                """, (rs, row) -> new ShippingOrder(rs.getLong("id"), rs.getLong("total_amount"),
                rs.getLong("subtotal_amount"), rs.getLong("shipping_fee"), rs.getString("payment_method"),
                rs.getString("delivery_name"), rs.getString("delivery_phone"), rs.getString("delivery_address"),
                rs.getString("delivery_province_name"), rs.getString("delivery_ward_name"), rs.getString("shop_name"),
                rs.getString("pickup_address"), rs.getString("pickup_province_name"), rs.getString("pickup_ward_name"),
                rs.getString("pickup_phone")), orderId, shopId);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy thông tin giao hàng.");
        ShippingOrder order = rows.get(0);
        if (blank(order.pickupProvince()) || blank(order.pickupWard()) || blank(order.pickupAddress()) || blank(order.pickupPhone())) {
            throw ApiException.badRequest("Cửa hàng cần bổ sung địa chỉ lấy hàng, tỉnh/phường và số điện thoại trong Hồ sơ cửa hàng.");
        }
        List<ShippingItem> items = jdbc.query("""
                SELECT p.display_name, oi.quantity_kg, oi.line_total
                FROM order_items oi JOIN shop_products p ON p.id = oi.shop_product_id
                WHERE oi.order_id = ? ORDER BY oi.id
                """, (rs, row) -> new ShippingItem(rs.getString("display_name"), rs.getBigDecimal("quantity_kg"),
                rs.getLong("line_total")), orderId);
        String toProvince = canonicalProvince(order.deliveryProvince());
        String toWard = canonicalWard(toProvince, order.deliveryWard());
        ShippingParty pickup = pickupParty(shopId);
        ShipmentSize size = size(items);
        long codAmount = "COD".equals(order.paymentMethod()) ? order.total() : 0;
        Map<String, Object> request = shipmentRequest(orderId, order.deliveryName(), order.deliveryPhone(),
                order.deliveryAddress(), toProvince, toWard, pickup, size, order.subtotal(), codAmount, true);
        JsonNode data = call("/v2/shipping-order/create", request).path("data");
        String orderCode = data.path("order_code").asText("");
        if (orderCode.isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "GHN chưa trả về mã vận đơn.");
        long carrierFee = data.path("total_fee").asLong(0);
        Instant expected = parseInstant(data.path("expected_delivery_time").asText(null));
        String clientOrderCode = clientOrderCode(orderId);
        jdbc.update("""
                INSERT INTO order_shipments
                    (order_id, provider, carrier_order_code, client_order_code, carrier_status,
                     carrier_fee, quoted_fee, cod_amount, expected_delivery_at)
                VALUES (?, 'GHN', ?, ?, 'ready_to_pick', ?, ?, ?, ?)
                ON CONFLICT (order_id) DO UPDATE SET provider = EXCLUDED.provider,
                    carrier_order_code = EXCLUDED.carrier_order_code,
                    client_order_code = EXCLUDED.client_order_code, carrier_status = EXCLUDED.carrier_status,
                    carrier_fee = EXCLUDED.carrier_fee, quoted_fee = EXCLUDED.quoted_fee,
                    cod_amount = EXCLUDED.cod_amount, expected_delivery_at = EXCLUDED.expected_delivery_at,
                    updated_at = NOW()
                """, orderId, orderCode, clientOrderCode, carrierFee, order.shippingFee(), codAmount,
                expected == null ? null : Timestamp.from(expected));
        return new ShipmentCreation(orderCode, "ready_to_pick", carrierFee, expected);
    }

    public void handleWebhook(JsonNode payload) {
        String orderCode = payload.path("OrderCode").asText("");
        String type = payload.path("Type").asText("");
        Instant eventTime = parseInstant(payload.path("Time").asText(null));
        if (orderCode.isBlank() || type.isBlank() || eventTime == null) {
            throw ApiException.badRequest("Webhook GHN thiếu mã đơn hoặc thời điểm sự kiện.");
        }
        List<Long> shipmentIds = jdbc.query("SELECT id FROM order_shipments WHERE carrier_order_code = ?",
                (rs, row) -> rs.getLong(1), orderCode);
        if (shipmentIds.isEmpty()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Vận đơn GHN chưa được lưu. Hãy thử gửi lại callback.");
        long shipmentId = shipmentIds.get(0);
        String status = payload.path("Status").asText("");
        String description = payload.path("Description").asText("");
        String reason = payload.path("Reason").asText("");
        long totalFee = payload.path("TotalFee").asLong(0);
        Instant codTransferAt = parseInstant(payload.path("CODTransferDate").asText(null));
        int inserted = jdbc.update("""
                INSERT INTO shipment_events (shipment_id, carrier_order_code, event_type, carrier_status,
                    event_time, description, reason, total_fee, cod_transfer_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (carrier_order_code, event_type, event_time) DO NOTHING
                """, shipmentId, orderCode, type, blank(status) ? null : status, Timestamp.from(eventTime),
                limit(description, 512), limit(reason, 512), totalFee > 0 ? totalFee : null,
                codTransferAt == null ? null : Timestamp.from(codTransferAt));
        if (inserted == 0) return;
        jdbc.update("""
                UPDATE order_shipments SET carrier_status = COALESCE(NULLIF(?, ''), carrier_status),
                    carrier_fee = CASE WHEN ? > 0 THEN ? ELSE carrier_fee END,
                    last_event_at = GREATEST(COALESCE(last_event_at, ?), ?), updated_at = NOW()
                WHERE id = ?
                """, status, totalFee, totalFee, Timestamp.from(eventTime), Timestamp.from(eventTime), shipmentId);
        if ("cod".equalsIgnoreCase(type) && codTransferAt != null) {
            List<CodOrder> orders = jdbc.query("""
                    UPDATE orders SET payment_status = 'PAID', paid_at = COALESCE(paid_at, ?)
                    WHERE id = (SELECT order_id FROM order_shipments WHERE id = ?)
                      AND payment_method = 'COD' AND payment_status = 'UNPAID'
                    RETURNING id, shop_id, payos_reference
                    """, (rs, row) -> new CodOrder(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("payos_reference")),
                    Timestamp.from(codTransferAt), shipmentId);
            if (!orders.isEmpty()) {
                CodOrder order = orders.get(0);
                wallet.recordPaidOrder(order.id(), order.shopId(), orderCode, null);
            }
        }
    }

    public boolean webhookSecretMatches(String candidate) {
        if (webhookSecret.isBlank() || candidate == null) return false;
        return java.security.MessageDigest.isEqual(webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Map<String, Object> shipmentRequest(long orderId, String recipientName, String recipientPhone,
            String deliveryAddress,
            String province, String ward, ShippingParty pickup, ShipmentSize size,
            long subtotal, long codAmount, boolean create) {
        if (codAmount < 0 || codAmount > 50_000_000) {
            throw ApiException.badRequest("GHN nhận thu hộ tối đa 50.000.000đ cho mỗi đơn.");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("payment_type_id", 1); // Golden Time pays GHN; the buyer pays the order total by COD if applicable.
        request.put("required_note", "CHOXEMHANGKHONGTHU");
        request.put("to_name", recipientName);
        request.put("to_phone", recipientPhone);
        request.put("to_district_name", "");
        if (create) request.put("client_order_code", clientOrderCode(orderId));
        request.put("from_name", pickup.name());
        request.put("from_phone", pickup.phone());
        request.put("from_address", pickup.address());
        request.put("from_ward_name", pickup.ward());
        request.put("from_province_name", pickup.province());
        request.put("is_new_from_address", true);
        request.put("return_name", pickup.name());
        request.put("return_phone", pickup.phone());
        request.put("return_address", pickup.address());
        request.put("return_ward_name", pickup.ward());
        request.put("return_province_name", pickup.province());
        request.put("return_district_name", "");
        request.put("is_new_return_address", true);
        request.put("to_address", deliveryAddress);
        request.put("to_ward_name", ward);
        request.put("to_province_name", province);
        request.put("is_new_to_address", true);
        request.put("from_district_name", "");
        request.put("weight", size.weightGrams());
        request.put("length", lengthCm);
        request.put("width", widthCm);
        request.put("height", heightCm);
        request.put("service_type_id", size.weightGrams() < 20_000 ? 2 : 5);
        request.put("cod_amount", codAmount);
        request.put("order_value", subtotal);
        request.put("content", limit("Đơn Golden Time " + orderId, 2000));
        request.put("items", List.of(Map.of("name", limit("Đơn hàng Golden Time " + orderId, 512),
                "quantity", 1, "price", subtotal, "length", lengthCm, "width", widthCm,
                "height", heightCm, "weight", size.weightGrams())));
        return request;
    }

    private ShippingParty pickupParty(long shopId) {
        List<ShippingParty> shops = jdbc.query("""
                SELECT s.name, COALESCE(NULLIF(s.business_phone, ''), NULLIF(s.representative_phone, ''), u.phone) AS phone,
                       s.address, s.pickup_province_name, s.pickup_ward_name
                FROM shops s LEFT JOIN users u ON u.id = s.owner_user_id WHERE s.id = ?
                """, (rs, row) -> new ShippingParty(rs.getString("name"), rs.getString("phone"),
                rs.getString("address"), rs.getString("pickup_province_name"), rs.getString("pickup_ward_name")), shopId);
        if (shops.isEmpty()) throw ApiException.notFound("Không tìm thấy cửa hàng.");
        ShippingParty party = shops.get(0);
        if (blank(party.province()) || blank(party.ward()) || blank(party.address()) || blank(party.phone())) {
            throw ApiException.badRequest("Cửa hàng cần bổ sung địa chỉ lấy hàng, tỉnh/phường và số điện thoại trong Hồ sơ cửa hàng.");
        }
        String province = canonicalProvince(party.province());
        String ward = canonicalWard(province, party.ward());
        return new ShippingParty(party.name(), party.phone(), party.address(), province, ward);
    }

    private ShipmentSize size(List<ShippingItem> items) {
        BigDecimal weight = items.stream().map(ShippingItem::quantityKg).reduce(BigDecimal.ZERO, BigDecimal::add)
                .multiply(BigDecimal.valueOf(1000)).add(BigDecimal.valueOf(packagingWeightGrams));
        int grams = weight.setScale(0, RoundingMode.CEILING).intValueExact();
        if (grams <= 0 || grams > 50_000) throw ApiException.badRequest("GHN nhận kiện hàng tối đa 50 kg.");
        return new ShipmentSize(grams);
    }

    private String canonicalProvince(String value) {
        if (blank(value)) throw ApiException.badRequest("Vui lòng chọn tỉnh/thành nhận hàng.");
        return provinces().stream().filter(item -> item.name().equals(value.trim())).map(GhnAddress::name).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Tỉnh/thành nhận hàng không thuộc danh mục GHN."));
    }

    private String canonicalWard(String province, String ward) {
        if (blank(ward)) throw ApiException.badRequest("Vui lòng chọn phường/xã nhận hàng.");
        GhnAddress provinceRecord = provinces().stream().filter(item -> item.name().equals(province)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Tỉnh/thành GHN không hợp lệ."));
        return wards(provinceRecord.id()).stream().filter(item -> item.name().equals(ward.trim())).map(GhnAddress::name)
                .findFirst().orElseThrow(() -> ApiException.badRequest("Phường/xã không thuộc tỉnh/thành đã chọn."));
    }

    private List<GhnAddress> addressList(JsonNode response) {
        JsonNode data = response.path("data");
        if (!data.isArray()) return List.of();
        List<GhnAddress> results = new ArrayList<>();
        for (JsonNode row : data) {
            long id = row.path("_id").asLong(row.path("ProvinceID").asLong(row.path("WardCode").asLong(0)));
            String name = row.path("name").asText(row.path("ProvinceName").asText(row.path("WardName").asText("")));
            if (id > 0 && !name.isBlank()) results.add(new GhnAddress(id, name));
        }
        return List.copyOf(results);
    }

    private JsonNode get(String path, Object... uriValues) {
        try {
            JsonNode response = client.get().uri(builder -> {
                UriBuilder uri = builder.path(path);
                if (uriValues.length > 0) uri.queryParam("province_id", uriValues[0]);
                uri.queryParam("limit", 200);
                return uri.build();
            }).headers(headers -> {
                headers.set("Token", token);
                headers.set("ShopId", Integer.toString(shopId));
            })
                    .retrieve().body(JsonNode.class);
            if (response == null || response.path("code").asInt(0) != 200) {
                String message = response == null ? "GHN không phản hồi." : response.path("message").asText("GHN từ chối yêu cầu.");
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GHN: " + limit(message, 300));
            }
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw upstreamError(exception);
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Không kết nối được dịch vụ giao hàng GHN.");
        }
    }

    private JsonNode call(String path, Map<String, Object> body) {
        try {
            JsonNode response = client.post().uri(path)
                    .headers(headers -> {
                        headers.set("Token", token);
                        headers.set("ShopId", Integer.toString(shopId));
                    })
                    .body(body).retrieve().body(JsonNode.class);
            if (response == null || response.path("code").asInt(0) != 200) {
                String message = response == null ? "GHN không phản hồi." : response.path("message").asText("GHN từ chối yêu cầu.");
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GHN: " + limit(message, 300));
            }
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw upstreamError(exception);
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Không kết nối được dịch vụ giao hàng GHN.");
        }
    }

    private static ApiException upstreamError(RestClientResponseException exception) {
        if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GHN chưa xác thực được tài khoản. Kiểm tra Token, Shop ID và quyền truy cập IP trên máy chủ.");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY,
                "GHN chưa chấp nhận yêu cầu giao hàng. Kiểm tra địa chỉ, khối lượng và cấu hình dịch vụ.");
    }

    private void requireConfigured() {
        if (!configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Chưa cấu hình tài khoản giao hàng GHN trên máy chủ.");
    }

    private static String clientOrderCode(long id) {
        return "GT-" + id;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String limit(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }

    private static Instant parseInstant(String value) {
        if (blank(value)) return null;
        try { return Instant.parse(value); } catch (RuntimeException ignored) { return null; }
    }

    private record ShippingOrder(long id, long total, long subtotal, long shippingFee, String paymentMethod,
            String deliveryName, String deliveryPhone, String deliveryAddress, String deliveryProvince,
            String deliveryWard, String shopName, String pickupAddress, String pickupProvince,
            String pickupWard, String pickupPhone) {}
    private record ShippingParty(String name, String phone, String address, String province, String ward) {}
    private record ShipmentSize(int weightGrams) {}

    public record ShippingItem(String name, BigDecimal quantityKg, long lineTotal) {}
    public record GhnAddress(long id, String name) {}
    public record Quote(long shippingFee, Instant expectedDeliveryAt) {}
    public record ShipmentCreation(String trackingCode, String carrierStatus, long carrierFee, Instant expectedDeliveryAt) {}
}
